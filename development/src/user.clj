(ns user
  (:require [clojure-land.core :as core]
            [integrant.core :as ig]))

(add-tap println)

(defonce ^:dynamic *system* nil)

(defn go []
  (let [system (core/start!)]
    (alter-var-root #'*system* (constantly system))))

(defn stop []
  (when *system*
    (alter-var-root #'*system* ig/halt!)))
