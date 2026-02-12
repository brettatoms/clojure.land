(ns clojure-land.projects
  (:require [clojure-land.clojars :as clojars]
            [clojure-land.github :as github]
            [clojure-land.logging :as log]
            [clojure-land.s3 :as s3]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [malli.core :as m]
            [malli.dev.pretty :as mp]
            [malli.dev.virhe :as mv]
            [malli.error :as me])
  (:import [java.time Duration Instant]))

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

(defn downloads-per-day
  "Calculate downloads per day from a project's recent downloads field.
   Finds the :downloads-past-Nd key and divides by N."
  [project]
  (let [recent-downloads-key (clojars/recent-downloads-key clojars/recent-downloads-days)]
    (when-let [recent-downloads (get project recent-downloads-key)]
      (/ (double recent-downloads) clojars/recent-downloads-days))))

(defn- to-instant [v]
  (cond
    (nil? v) nil
    (string? v) (.toInstant (java.time.OffsetDateTime/parse v))
    (instance? java.util.Date v) (.toInstant v)
    :else (.toInstant v)))

(defn- staleness-decay
  "Calculate a decay factor based on how long since the project was last active.
   For Clojars projects, uses the latest release date since that reflects when
   users last got a new version. For non-Clojars projects, uses last push date.
   Returns 1.0 for projects active within 180 days, then decreases by 0.1 per
   year, with a minimum of 0.3."
  [{:keys [latest-release-date last-pushed-at group-id artifact-id repository]}]
  (let [clojars? (and group-id artifact-id
                      (or (nil? repository) (= repository :clojars)))
        last-instant (if clojars?
                       (or (to-instant latest-release-date) (to-instant last-pushed-at))
                       (or (to-instant last-pushed-at) (to-instant latest-release-date)))
        days-stale (if last-instant
                     (.toDays (Duration/between last-instant (Instant/now)))
                     1000)]
    (if (< days-stale 180)
      1.0
      (max 0.3 (- 1.0 (min 0.7 (* 0.1 (/ (- days-stale 180) 365.0))))))))

(defn- archived-penalty
  "Penalize archived projects. Returns 0.1 for archived projects, 1.0 otherwise."
  [project]
  (if (:archived project) 0.1 1.0))

(defn popularity-score
  "Calculate a popularity score for a project.

   Uses the geometric mean of stars and downloads-per-day when download data is
   available, otherwise falls back to stars alone. The geometric mean naturally
   balances both signals — transitive dependencies with high downloads but few
   stars are dampened, while projects need both community interest (stars) and
   real-world usage (downloads) to rank highly.

   The result is further adjusted by staleness decay and an archived penalty."
  [project]
  (let [stars (double (or (:stars project) 0))
        dpd (downloads-per-day project)
        base (if (and dpd (pos? dpd))
               (Math/sqrt (* stars dpd))
               stars)]
    (* base
       (staleness-decay project)
       (archived-penalty project))))

(defn fetch-remote-projects [s3-client bucket-name]
  (-> (s3/get-object s3-client {:Bucket bucket-name
                                :Key "projects.edn"})

      :Body
      slurp
      edn/read-string))

(defn- enrich-with-github
  "Enrich project with GitHub data if it has a GitHub URL."
  [github-client {:keys [repo-url url key] :as project}]
  (if (re-matches #".*?github.*?" (or repo-url url ""))
    (try
      (github/update-project-from-repo github-client project)
      (catch Exception e
        (log/error e "Failed to enrich project from GitHub:" key)
        project))
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
  "Generate a timestamped S3 key like 'projects-2026-01-25T02-00-00.00Z.edn'"
  [now]
  (let [formatter (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss.SS'Z'")
        ts (.format (.atZone now java.time.ZoneOffset/UTC) formatter)]
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
                       (->> project
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
           content (pr-str (vec projects))
           _ (log/info "Serialized" (count content) "chars")
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
