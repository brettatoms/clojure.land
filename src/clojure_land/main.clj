(ns clojure-land.main
  (:gen-class)
  (:require  [clojure-land.core :as core]
             [clojure.tools.logging :as log]))

(defn -main [& _]
  (core/start!)
  (log/info "Server started...")
  ;; block until process is killed
  (.join (Thread/currentThread)))
