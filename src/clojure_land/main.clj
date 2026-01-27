(ns clojure-land.main
  (:gen-class)
  (:require [clojure-land.core :as clj-land]
            [clojure-land.logging :as log]
            [zodiac.core :as z]))

(defn -main [& _]
  (let [system (clj-land/start!)
        ;; Get the Jetty server from the nested zodiac system
        jetty-server (get-in system [::clj-land/zodiac ::z/jetty])]
    (log/info "Server started...")
    ;; Block on the Jetty server thread
    (.join jetty-server)))
