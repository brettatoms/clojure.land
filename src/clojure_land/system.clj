(ns clojure-land.system
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [integrant.core :as ig]))

(defmethod aero/reader 'ig/ref
  [_opts _tag value]
  (ig/ref value))

(defn start! []
  (let [config (-> (io/resource "clojure.land/system.edn")
                   (aero/read-config))]
    (ig/load-namespaces config)
    (ig/init config)))
