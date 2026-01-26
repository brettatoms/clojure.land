(ns clojure-land.projects
  (:require [clojure-land.clojars :as clojars]
            [clojure-land.github :as github]
            [clojure-land.s3 :as s3]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [malli.dev.pretty :as mp]
            [malli.dev.virhe :as mv]
            [malli.error :as me])
  (:import [java.time Instant]))

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
   (let [now (Instant/now)
         token (System/getenv "GITHUB_API_TOKEN")
         github-client (github/->GitHubClient (github/request-factory token))
         clojars-stats (clojars/fetch-stats)
         clojars-recent-stats (clojars/fetch-recent-stats clojars/recent-downloads-days)
         clojars-feed (clojars/fetch-feed)
         projects (->> (read-projects-edn)
                       (remove :ignore)
                       (pmap (fn [{:keys [key] :as project}]
                               (log/debug "Enriching project:" key)
                               (-> project
                                   (enrich-with-github github-client)
                                   (enrich-with-clojars clojars-stats clojars-recent-stats clojars-feed)))))
         s3-client (s3/client {:endpoint-url (System/getenv "AWS_ENDPOINT_URL_S3")
                               :access-key-id (System/getenv "AWS_ACCESS_KEY_ID")
                               :secret-access-key (System/getenv "AWS_SECRET_ACCESS_KEY")})
         bucket (System/getenv "BUCKET_NAME")
         content (pr-str (vec projects))]
     ;; Write timestamped version (historical record)
     (log/info "Writing timestamped snapshot:" (timestamp-key now))
     (s3/put-object s3-client {:Bucket bucket
                               :Key (timestamp-key now)
                               :Body content})
     ;; Write latest version (app reads this)
     (log/info "Writing latest projects.edn")
     (s3/put-object s3-client {:Bucket bucket
                               :Key "projects.edn"
                               :Body content}))))

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
