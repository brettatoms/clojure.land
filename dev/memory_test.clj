(ns memory-test
  "Script to debug memory usage during sync. 
   Caches GitHub responses to avoid rate limits on repeated runs."
  (:require [clojure-land.clojars :as clojars]
            [clojure-land.github :as github]
            [clojure-land.projects :as projects]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def cache-dir "tmp/github-cache")

(defn mb [bytes] (format "%.1f MB" (/ bytes 1024.0 1024.0)))

(defn mem-stats [label]
  (System/gc)
  (Thread/sleep 100)
  (let [rt (Runtime/getRuntime)
        used (- (.totalMemory rt) (.freeMemory rt))
        total (.totalMemory rt)
        max (.maxMemory rt)]
    (println (format "[MEMORY %-30s] used: %8s  allocated: %8s  max: %s"
                     label (mb used) (mb total) (mb max)))))

(defn cache-path [repo-name]
  (let [safe-name (-> repo-name
                      (clojure.string/replace "/" "_")
                      (clojure.string/replace "." "_"))]
    (io/file cache-dir (str safe-name ".edn"))))

(defn cached-get-repo [github-client repo-name]
  (let [path (cache-path repo-name)]
    (if (.exists path)
      (edn/read-string (slurp path))
      (let [result (.get-repo github-client repo-name)]
        (.mkdirs (io/file cache-dir))
        (spit path (pr-str result))
        result))))

;; Wrap the GitHub client to use caching
(deftype CachedGitHubClient [delegate]
  github/GitHubClientProtocol
  (get-repo [_ name]
    (cached-get-repo delegate name)))

(defn run-test []
  (mem-stats "start")
  
  (let [projects-to-sync (doall (remove :ignore (projects/read-projects-edn)))]
    (mem-stats "after read-projects")
    (println "Project count:" (count projects-to-sync))
    
    (let [clojars-keys (->> projects-to-sync
                            (keep (fn [{:keys [group-id artifact-id]}]
                                    (when (and group-id artifact-id)
                                      [group-id artifact-id])))
                            set)]
      (println "Clojars keys:" (count clojars-keys))
      
      (let [clojars-stats (clojars/fetch-stats clojars-keys)]
        (mem-stats "after fetch-stats")
        
        (let [clojars-recent-stats (clojars/fetch-recent-stats clojars/recent-downloads-days clojars-keys)]
          (mem-stats "after fetch-recent-stats")
          
          (let [clojars-feed (clojars/fetch-feed clojars-keys)]
            (mem-stats "after fetch-feed")
            
            (let [token (System/getenv "GITHUB_API_TOKEN")
                  github-client (->CachedGitHubClient 
                                  (github/->GitHubClient (github/request-factory token)))
                  
                  enrich-fn (fn [{:keys [key repo-url url] :as project}]
                              (-> project
                                  (#(if (re-matches #".*?github.*?" (or repo-url url ""))
                                      (github/update-project-from-repo github-client %)
                                      %))
                                  (#(if (= (:repository %) :maven-central)
                                      %
                                      (let [downloads (clojars/get-downloads clojars-stats %)
                                            recent-downloads (clojars/get-recent-downloads clojars-recent-stats %)
                                            artifact-info (clojars/get-artifact-info clojars-feed %)]
                                        (cond-> %
                                          downloads (assoc :downloads downloads)
                                          recent-downloads (assoc :downloads-past-10d recent-downloads)
                                          (:latest-version artifact-info) (assoc :latest-version (:latest-version artifact-info))
                                          (:latest-release-date artifact-info) (assoc :latest-release-date (:latest-release-date artifact-info))
                                          (and (not (:description %)) (:description artifact-info)) (assoc :description (:description artifact-info))))))))
                  
                  ;; Process in batches with memory logging
                  batches (partition-all 100 projects-to-sync)
                  batch-count (count batches)]
              
              (println "Processing" batch-count "batches of 100 projects...")
              
              (let [enriched (loop [remaining batches
                                    batch-num 1
                                    result []]
                               (if (empty? remaining)
                                 result
                                 (let [batch (first remaining)
                                       enriched-batch (doall (pmap enrich-fn batch))
                                       new-result (into result enriched-batch)]
                                   (when (zero? (mod batch-num 5))
                                     (mem-stats (str "after batch " batch-num "/" batch-count)))
                                   (recur (rest remaining) (inc batch-num) new-result))))]
                
                (mem-stats "after all enrichment")
                (println "Enriched" (count enriched) "projects")
                
                (println "Running pr-str...")
                (let [content (pr-str (vec enriched))]
                  (mem-stats "after pr-str")
                  (println "Content size:" (count content) "chars")
                  (println "Content size:" (mb (* 2 (count content))))
                  
                  (spit "tmp/enriched-output.edn" content)
                  (println "Written to tmp/enriched-output.edn"))))))))))

(defn -main [& args]
  (run-test))

(comment
  (run-test))
