(ns clojure-land.clojars
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hato.client :as hc])
  (:import [java.util.zip GZIPInputStream]))

(def stats-url "https://clojars.org/stats/all.edn")
(def feed-url "https://clojars.org/repo/feed.clj.gz")

(def ^:private http-client
  (delay (hc/build-http-client {:redirect-policy :always})))

;; --- Stats file (download counts) ---

(defn fetch-stats
  "Fetch Clojars stats file. Returns map of [group artifact] -> total-downloads.
   Downloads are summed across all versions."
  []
  (log/info "Fetching Clojars stats from" stats-url)
  (let [response (hc/get stats-url {:http-client @http-client
                                    :as :string})
        raw (edn/read-string (:body response))]
    (log/info "Fetched stats for" (count raw) "artifacts")
    (into {}
          (map (fn [[k version-downloads]]
                 [k (reduce + (vals version-downloads))]))
          raw)))

(defn get-downloads
  "Look up total downloads for a project. Returns nil if not found."
  [stats-map {:keys [group-id artifact-id]}]
  (when (and group-id artifact-id)
    (get stats-map [group-id artifact-id])))

;; --- Feed file (version info) ---

(defn fetch-feed
  "Fetch Clojars feed file (gzipped). Returns map of [group artifact] -> artifact-info.
   Each artifact-info contains :versions, :versions-meta, :description, etc."
  []
  (log/info "Fetching Clojars feed from" feed-url)
  (let [response (hc/get feed-url {:http-client @http-client
                                   :as :stream})
        artifacts (with-open [gzip (GZIPInputStream. (:body response))
                              rdr (io/reader gzip)]
                    (doall
                     (for [line (line-seq rdr)
                           :when (not (str/blank? line))]
                       (edn/read-string line))))]
    (log/info "Fetched feed for" (count artifacts) "artifacts")
    (into {}
          (map (fn [{:keys [group-id artifact-id] :as artifact}]
                 [[group-id artifact-id] artifact]))
          artifacts)))

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
