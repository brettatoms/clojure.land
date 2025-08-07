(ns clojure-land.projects
  (:require [clojure-land.github :as github]
            [clojure-land.s3 :as s3]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.dev.pretty :as mp]
            [malli.dev.virhe :as mv]
            [malli.error :as me]))

(def Project
  [:map
   [:name :string]
   [:url :string]
   [:key :keyword]
   [:repo-url {:optional true} [:maybe :string]]
   [:tags {:optional true} [:set :string]]
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

(defn sync-remote-projects-edn
  "Update the local projects.edn file with the repo data and store in Tigris/S3."
  ([]
   (sync-remote-projects-edn nil))
  ;; The single arg version is so we can run via a deps.edn alias
  ([_]
   (let [token (System/getenv "GITHUB_API_TOKEN")
         request (github/request-factory token)
         github-client (github/->GitHubClient request)
         projects (->> (read-projects-edn)
                       (remove :ignore)
                       (filter #(some? (:repo-url %)))
                       (filter #(re-matches #".*?github.*?" (:repo-url %)))
                       (pmap (fn [{:keys [key repo-url] :as project}]
                               (log/debug "Updating project: " key)
                               (if (re-matches #".*?github.*?" repo-url)
                                 (github/update-project-from-repo github-client project)
                                 project))))
         s3-client (s3/client {:endpoint-url (System/getenv "AWS_ENDPOINT_URL_S3")
                               :access-key-id (System/getenv "AWS_ACCESS_KEY_ID")
                               :secret-access-key (System/getenv "AWS_SECRET_ACCESS_KEY")})]
     (s3/put-object s3-client {:Bucket (System/getenv "BUCKET_NAME")
                               :Key "project.edn"
                               :Body (pr-str projects)}))))

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
