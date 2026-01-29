(ns clojure-land.db
  (:require [clojure-land.projects :as projects]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]
            [zodiac.ext.sql :as z.sql]))

(defmethod ig/init-key ::create [_ {:keys [zodiac]}]
  (let [db (::z.sql/db zodiac)]
    (z.sql/execute! db {:drop-table [:if-exists :project]})
    (z.sql/execute! db {:create-table [:project :if-not-exists]
                        :with-columns [[:archived :boolean]
                                       [:artifact_id :text]
                                       [:description :text]
                                       [:downloads_per_day :real]
                                       [:group_id :text]
                                       [:last_pushed_at [:raw "timestamp with time zone"]]
                                       [:latest_release_date [:raw "timestamp with time zone"]]
                                       [:latest_version :text]
                                       #_["key" :text [:not nil]]
                                       [:platforms :text :array]
                                       [:name :text [:not nil]]
                                       [:popularity_score :real]
                                       [:repo-url :text]
                                       [:repository :text]
                                       [:tags :text :array]
                                       [:stars :int]
                                       [:url :text [:not nil]]]})))

(defn- to-lower-case-array [values]
  (->> values
       (mapv str/lower-case)
       (to-array)))

(defmethod ig/init-key ::populate [_ {:keys [bucket-name local-projects? s3 zodiac]}]
  (let [db (::z.sql/db zodiac) ;; get the database connection from zodiac
        data (if local-projects?
               (-> (io/resource "clojure.land/projects.edn")
                   (slurp)
                   (edn/read-string))
               (projects/fetch-remote-projects s3 bucket-name))
        active-data (remove :ignore data)
        ;; The columns need to be in the same order as the :with-columns when creating the table
        columns [#_:key :archived :artifact-id :description :downloads-per-day
                 :group-id :last-pushed-at :latest-release-date :latest-version
                 :platforms :name :popularity-score :repo-url :repository
                 :stars :tags :url]
        empty-row (zipmap columns (repeat nil))
        normalize (fn [p]
                    (-> (merge empty-row p)
                        (assoc :popularity-score (projects/popularity-score p))
                        (assoc :downloads-per-day (projects/downloads-per-day p))
                        (update :repository #(when % (name %)))
                        (update :tags to-lower-case-array)
                        (update :platforms to-lower-case-array)))
        values (->> active-data
                    (map normalize)
                    ;; get the columns values in order (without the keys)
                    (map #(map % columns)))]
    (z.sql/execute! db {:insert-into :project
                        :columns columns
                        :values values})))
