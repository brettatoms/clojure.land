(ns clojure-land.db
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [taoensso.telemere] ;; setup logging side-effects
            [zodiac.ext.sql :as z.sql]))

(defmethod ig/init-key ::init [_ {:keys [zodiac]}]
  (let [db (::z.sql/db zodiac) ;; get the database connection from zodiac
        data (-> (io/resource "clojure.land/projects.edn")
                 (slurp)
                 (clojure.edn/read-string))
        ;; The columns need to be in the same order as the :with-columns when creating the table
        columns [#_:key :description :platforms :name :repo-url :tags :title :url]
        empty-row  (zipmap columns (repeat nil))
        normalize (fn [p]
                    (-> (merge empty-row p)
                        (update :tags to-array)
                        (update :platforms to-array)))
        values (->> data
                    (remove :hide)
                    (map normalize)
                    ;; get the columns values in order (without the keys)
                    (map #(map % columns)))]
    (z.sql/execute! db {:drop-table [:if-exists :projects]})
    (z.sql/execute! db {:create-table [:project :if-not-exists]
                        :with-columns [[:description :text]
                                       #_["key" :text [:not nil]]
                                       [:platforms :text :array]
                                       [:name :text [:not nil]]
                                       [:repo-url :text]
                                       [:tags :text :array]
                                       [:title :text [:not nil]]
                                       [:url :text [:not nil]]]})
    (z.sql/execute! db {:insert-into :project
                        ;; :columns columns
                        :values values})))
