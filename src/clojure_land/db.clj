(ns clojure-land.db
  (:require [clojure-land.projects :as projects]
            [integrant.core :as ig]
            [zodiac.ext.sql :as z.sql]))

(defmethod ig/init-key ::create [_ {:keys [zodiac]}]
  (let [db (::z.sql/db zodiac)]
    (z.sql/execute! db {:drop-table [:if-exists :project]})
    (z.sql/execute! db {:create-table [:project :if-not-exists]
                        :with-columns [[:description :text]
                                       #_["key" :text [:not nil]]
                                       [:platforms :text :array]
                                       [:name :text [:not nil]]
                                       [:repo-url :text]
                                       [:tags :text :array]
                                       [:stars :int]
                                       [:url :text [:not nil]]]})))

(defmethod ig/init-key ::populate [_ {:keys [bucket-name s3 zodiac]}]
  (let [db (::z.sql/db zodiac) ;; get the database connection from zodiac
        ;; data (-> (clojure.java.io/resource "clojure.land/projects.edn")
        ;;          (slurp)
        ;;          (clojure.edn/read-string))
        data (projects/fetch-remote-projects s3 bucket-name)
        ;; The columns need to be in the same order as the :with-columns when creating the table
        columns [#_:key :description :platforms :name :repo-url :stars :tags :url]
        empty-row  (zipmap columns (repeat nil))
        normalize (fn [p]
                    (-> (merge empty-row p)
                        (update :tags to-array)
                        (update :platforms to-array)))
        values (->> data
                    (remove :ignore)
                    (map normalize)
                    ;; get the columns values in order (without the keys)
                    (map #(map % columns)))]
    (z.sql/execute! db {:insert-into :project
                        :columns columns
                        :values values})))
