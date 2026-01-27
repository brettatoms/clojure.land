(ns clojure-land.clojars
  (:require [clojure-land.logging :as log]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hato.client :as hc])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.util.zip GZIPInputStream]))

(def stats-url "https://clojars.org/stats/all.edn")
(def feed-url "https://clojars.org/repo/feed.clj.gz")
(def daily-stats-url-template "https://repo.clojars.org/stats/downloads-%s.edn")

(def recent-downloads-days
  "Number of days to include in recent downloads count."
  10)

(defn recent-downloads-key
  "Returns the keyword for recent downloads based on the number of days.
   e.g., 10 -> :downloads-past-10d, 32 -> :downloads-past-32d"
  [days]
  (keyword (str "downloads-past-" days "d")))

(def ^:private http-client
  (delay (hc/build-http-client {:redirect-policy :always})))

;; --- Stats file (download counts) ---

(defn fetch-stats
  "Fetch Clojars stats file. Returns map of [group artifact] -> total-downloads.
   Downloads are summed across all versions.
   If artifact-keys is provided, only those artifacts are kept."
  ([]
   (fetch-stats nil))
  ([artifact-keys]
   (log/info "Fetching Clojars stats from" stats-url)
   (let [response (hc/get stats-url {:http-client @http-client
                                     :as :string})
         raw (edn/read-string (:body response))
         stats (into {}
                     (map (fn [[k version-downloads]]
                            [k (reduce + (vals version-downloads))]))
                     raw)]
     (log/info "Fetched stats for" (count raw) "artifacts"
               (when artifact-keys (str "(filtering to " (count artifact-keys) ")")))
     (if artifact-keys
       (select-keys stats artifact-keys)
       stats))))

(defn get-downloads
  "Look up total downloads for a project. Returns nil if not found."
  [stats-map {:keys [group-id artifact-id]}]
  (when (and group-id artifact-id)
    (get stats-map [group-id artifact-id])))

;; --- Daily stats files (recent downloads) ---

(def ^:private daily-stats-date-formatter
  (DateTimeFormatter/ofPattern "yyyyMMdd"))

(defn- fetch-daily-stats
  "Fetch a single daily stats file. Returns map of [group artifact] -> downloads for that day,
   or nil if the file doesn't exist."
  [date]
  (let [date-str (.format date daily-stats-date-formatter)
        url (format daily-stats-url-template date-str)]
    (try
      (log/debug "Fetching daily stats for" date-str)
      (let [response (hc/get url {:http-client @http-client
                                  :as :string})]
        (when (= 200 (:status response))
          (edn/read-string (:body response))))
      (catch Exception e
        (log/warn "Failed to fetch daily stats for" date-str ":" (.getMessage e))
        nil))))

(defn fetch-recent-stats
  "Fetch daily stats for the past n days and sum them.
   Returns map of [group artifact] -> total downloads over the period.
   If artifact-keys is provided, only those artifacts are kept."
  ([days]
   (fetch-recent-stats days nil))
  ([days artifact-keys]
   (log/info "Fetching daily stats for past" days "days")
   (let [today (LocalDate/now)
         dates (map #(.minusDays today %) (range 1 (inc days)))
         daily-stats (->> dates
                          (pmap fetch-daily-stats)
                          (filter some?))
         stats (reduce (fn [acc day-stats]
                         (reduce-kv (fn [m k v]
                                      (update m k (fnil + 0) (reduce + (vals v))))
                                    acc
                                    day-stats))
                       {}
                       daily-stats)]
     (log/info "Fetched" (count daily-stats) "of" days "daily stats files"
               (when artifact-keys (str "(filtering to " (count artifact-keys) ")")))
     (if artifact-keys
       (select-keys stats artifact-keys)
       stats))))

(defn get-recent-downloads
  "Look up recent downloads for a project. Returns nil if not found."
  [recent-stats-map {:keys [group-id artifact-id]}]
  (when (and group-id artifact-id)
    (get recent-stats-map [group-id artifact-id])))

;; --- Feed file (version info) ---

(defn fetch-feed
  "Fetch Clojars feed file (gzipped). Returns map of [group artifact] -> artifact-info.
   Only keeps :versions, :versions-meta, and :description to minimize memory.
   If artifact-keys is provided, only those artifacts are kept."
  ([]
   (fetch-feed nil))
  ([artifact-keys]
   (log/info "Fetching Clojars feed from" feed-url)
   (let [response (hc/get feed-url {:http-client @http-client
                                    :as :stream})
         artifact-keys-set (when artifact-keys (set artifact-keys))
         artifacts (with-open [gzip (GZIPInputStream. (:body response))
                               rdr (io/reader gzip)]
                     (doall
                      (for [line (line-seq rdr)
                            :when (not (str/blank? line))
                            :let [artifact (edn/read-string line)
                                  k [(:group-id artifact) (:artifact-id artifact)]]
                            :when (or (nil? artifact-keys-set)
                                      (contains? artifact-keys-set k))]
                        ;; Only keep fields we actually use in get-artifact-info
                        (select-keys artifact [:group-id :artifact-id :versions :versions-meta :description]))))]
     (log/info "Fetched feed for" (count artifacts) "artifacts"
               (when artifact-keys (str "(filtered from full feed)")))
     (into {}
           (map (fn [{:keys [group-id artifact-id] :as artifact}]
                  [[group-id artifact-id] artifact]))
           artifacts))))

(defn stable-version?
  "Returns true if version string does not contain pre-release qualifiers.
   Filters out: SNAPSHOT, alpha, beta, RC, cr, milestone, preview"
  [version]
  (not (re-find #"(?i)(-SNAPSHOT|[-.]alpha|[-.]beta|[-.]RC\d*|[-.]cr\d*|[-.]milestone|[-.]preview)" version)))

(defn latest-stable-version
  "Find the latest stable version from a versions list.
   Versions are assumed to be ordered newest-first (as in Clojars feed)."
  [versions]
  (->> versions
       (filter stable-version?)
       first))

(defn get-artifact-info
  "Look up artifact info for a project. Returns map with :latest-version,
   :latest-release-date, and :description, or nil if not found."
  [feed-map {:keys [group-id artifact-id]}]
  (when (and group-id artifact-id)
    (when-let [{:keys [versions versions-meta description]} (get feed-map [group-id artifact-id])]
      (let [stable-version (latest-stable-version versions)
            version-meta (when stable-version
                           (->> versions-meta
                                (filter #(= (:version %) stable-version))
                                first))]
        (cond-> {}
          stable-version (assoc :latest-version stable-version)
          (:release-date version-meta) (assoc :latest-release-date (:release-date version-meta))
          description (assoc :description description))))))
