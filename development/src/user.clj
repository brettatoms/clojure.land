(ns user
  (:require [clojure-land.core :as core]
            [integrant.core :as ig]
            [zodiac.core :as z]
            [zodiac.ext.sql :as z.sql]))

(add-tap println)

(defonce ^:dynamic *system* nil)

(def ^:dynamic *db* nil)

(defn go []
  (let [system (core/start!)]
    (alter-var-root #'*system* (constantly system))
    (alter-var-root #'*db* (constantly (get-in *system* [::core/zodiac ::z.sql/db])))))

(defn stop []
  (when *system*
    (alter-var-root #'*system* ig/halt!)
    (alter-var-root #'*db* (constantly nil))))
