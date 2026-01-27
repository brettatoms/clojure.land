(ns clojure-land.projects
  (:require [clojure-land.clojars :as clojars]
            [clojure-land.github :as github]
            [clojure-land.logging :as log]
            [clojure-land.s3 :as s3]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.dev.pretty :as mp]
            [malli.dev.virhe :as mv]
            [malli.error :as me])
  (:import [java.time Instant]))

(defn- log-memory-stats
  "Log current memory usage stats."
  [label]
  (let [runtime (Runtime/getRuntime)
        mb (fn [bytes] (format "%.1f MB" (/ bytes 1024.0 1024.0)))
        used (- (.totalMemory runtime) (.freeMemory runtime))
        total (.totalMemory runtime)
        max (.maxMemory runtime)]
    (log/info (format "[MEMORY %s] used: %s, allocated: %s, max: %s"
                      label (mb used) (mb total) (mb max)))))

(def Project
  [:map
   [:name :string]
   [:url :string]
   [:key :keyword]
   [:repo-url {:optional true} [:maybe :string]]
   [:tags {:optional true} [:maybe [:set :string]]]
   [:platforms  [:set [:enum "clj" "cljs" "babashka"]]]
   [:ignore {:optional true} :boolean]])

(defn read-projects-edn []
  (-> (io/resource "clojure.land/projects.edn")
      (slurp)
      (edn/read-string)))

(defn fetch-remote-projects [s3-client bucket-name]
  (-> (s3/get-object s3-client {:Bucket bucket-name
                                :Key "project.edn"})

      :Body
      slurp
      edn/read-string))

(defn- enrich-with-github
  "Enrich project with GitHub data if it has a GitHub URL."
  [github-client {:keys [repo-url url] :as project}]
  (if (re-matches #".*?github.*?" (or repo-url url ""))
    (github/update-project-from-repo github-client project)
    project))

(defn- enrich-with-clojars
  "Enrich project with Clojars data if it has Maven coordinates.
   Adds :downloads, :downloads-past-Nd, :latest-version, :latest-release-date, and :description (as fallback).
   Skips projects with :repository :maven-central."
  [clojars-stats clojars-recent-stats clojars-feed {:keys [repository description] :as project}]
  (if (= repository :maven-central)
    project
    (let [downloads (clojars/get-downloads clojars-stats project)
          recent-downloads (clojars/get-recent-downloads clojars-recent-stats project)
          recent-downloads-key (clojars/recent-downloads-key clojars/recent-downloads-days)
          artifact-info (clojars/get-artifact-info clojars-feed project)]
      (cond-> project
        downloads (assoc :downloads downloads)
        recent-downloads (assoc recent-downloads-key recent-downloads)
        (:latest-version artifact-info) (assoc :latest-version (:latest-version artifact-info))
        (:latest-release-date artifact-info) (assoc :latest-release-date (:latest-release-date artifact-info))
        ;; Use Clojars description only as fallback if no description exists
        (and (not description) (:description artifact-info)) (assoc :description (:description artifact-info))))))

(defn- artifact-keys
  "Extract set of [group-id artifact-id] pairs from projects for Clojars lookup."
  [projects]
  (->> projects
       (keep (fn [{:keys [group-id artifact-id]}]
               (when (and group-id artifact-id)
                 [group-id artifact-id])))
       set))

(defn- timestamp-key
  "Generate a timestamped S3 key like 'projects-2026-01-25T02-00-00Z.edn'"
  [now]
  (let [ts (-> (.toString now)
               (str/replace ":" "-"))]
    (str "projects-" ts ".edn")))

(defn sync-remote-projects-edn
  "Update the local projects.edn file with GitHub and Clojars data and store in Tigris/S3.

   Writes two files:
   - projects.edn - latest sync, always overwritten
   - projects-{timestamp}.edn - historical snapshot, never overwritten"
  ([]
   (sync-remote-projects-edn nil))
  ;; The single arg version is so we can run via a deps.edn alias
  ([_]
   (try
     (log-memory-stats "start")
     (let [now (Instant/now)
           token (System/getenv "GITHUB_API_TOKEN")
           github-client (github/->GitHubClient (github/request-factory token))
           projects-to-sync (doall (remove :ignore (read-projects-edn)))
           _ (log-memory-stats "after read-projects-edn")
           clojars-keys (artifact-keys projects-to-sync)
           _ (log/info "Fetching Clojars data for" (count clojars-keys) "artifacts")
           clojars-stats (clojars/fetch-stats clojars-keys)
           _ (log-memory-stats "after fetch-stats")
           clojars-recent-stats (clojars/fetch-recent-stats clojars/recent-downloads-days clojars-keys)
           _ (log-memory-stats "after fetch-recent-stats")
           clojars-feed (clojars/fetch-feed clojars-keys)
           _ (log-memory-stats "after fetch-feed")
           enrich-fn (fn [{:keys [key] :as project}]
                       (log/debug "Enriching project:" key)
                       (-> project
                           (enrich-with-github github-client)
                           (enrich-with-clojars clojars-stats clojars-recent-stats clojars-feed)))
           projects (->> projects-to-sync
                         (partition-all 4)
                         (mapcat #(doall (pmap enrich-fn %)))
                         doall)
           _ (log-memory-stats "after enrichment")
           s3-client (s3/client {:endpoint-url (System/getenv "AWS_ENDPOINT_URL_S3")
                                 :access-key-id (System/getenv "AWS_ACCESS_KEY_ID")
                                 :secret-access-key (System/getenv "AWS_SECRET_ACCESS_KEY")})
           bucket (System/getenv "BUCKET_NAME")
           _ (log/info "Serializing" (count projects) "projects")
           ;; Serialize each project individually to find any problematic ones
           _ (doseq [[idx p] (map-indexed vector projects)]
               (let [s (pr-str p)
                     size (count s)]
                 (when (> size 1000)
                   (log/warn "Large project" idx (:key p) "size:" size))
                 (when (zero? (mod idx 100))
                   (log/debug "Serialized" idx "projects"))))
           _ (log/info "Individual serialization complete, building full content")
           ;; Use StringWriter to build content incrementally
           content (let [sw (java.io.StringWriter.)]
                     (.write sw "[")
                     (doseq [[idx p] (map-indexed vector projects)]
                       (when (pos? idx) (.write sw " "))
                       (binding [*out* sw]
                         (pr p))
                       (when (zero? (mod idx 100))
                         (log/debug "Written" idx "projects")))
                     (.write sw "]")
                     (.toString sw))
           _ (log/info "Content size:" (count content) "chars")
           _ (log-memory-stats "after pr-str")]
       ;; Write timestamped version (historical record)
       (log/info "Writing timestamped snapshot:" (timestamp-key now))
       (s3/put-object s3-client {:Bucket bucket
                                 :Key (timestamp-key now)
                                 :Body content})
       ;; Write latest version (app reads this)
       (log/info "Writing latest projects.edn")
       (s3/put-object s3-client {:Bucket bucket
                                 :Key "projects.edn"
                                 :Body content}))
     (catch OutOfMemoryError e
       (log-memory-stats "OOM")
       (log/error "OutOfMemoryError occurred!")
       (log/error "Stack trace:")
       (.printStackTrace e)
       (throw e)))))

;; override the ::m/explain formatter so we print out the specific project record that
;; doesn't validate rather than the entire projects.edn map
(defmethod mv/-format ::m/explain [_ {:keys [errors schema value] :as explanation} printer]
  {:body [:group
          (mv/-block "Value" (mv/-visit (get-in value (-> errors first :in butlast)) printer) printer) :break :break
          (mv/-block "Errors" (mv/-visit (me/humanize (me/with-spell-checking explanation)) printer) printer) :break :break
          (mv/-block "Schema" (mv/-visit schema printer) printer) :break :break
          (mv/-block "More information" (mv/-link "https://cljdoc.org/d/metosin/malli/CURRENT" printer) printer)]})

(defn validate-projects
  ([]
   (-> (io/resource "clojure.land/projects.edn")
       (slurp)
       (edn/read-string)
       (validate-projects)))
  ([projects]
   (mp/explain [:sequential Project] projects)))

(defn -validate-projects-exec-fn [& _]
  (when (seq (validate-projects))
    (System/exit 1)))

(comment
  (let [s3-client (s3/client {:endpoint-url (System/getenv "AWS_ENDPOINT_URL_S3")
                              :access-key-id (System/getenv "AWS_ACCESS_KEY_ID")
                              :secret-access-key (System/getenv "AWS_SECRET_ACCESS_KEY")})]
    (fetch-remote-projects s3-client "clojure.land"))
  ())
