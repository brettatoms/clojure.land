(ns clojure-land.routes.core
  (:require [clojure-land.routes.index :as index]
            [clojure.java.io :as io]
            [ring.middleware.logger :as ring.logger]))

(defn routes []
  ["" {:middleware [[ring.logger/wrap-with-logger]]}
   ["/" {:get #'index/handler}]
   ["/assets/clojure-land-og-image.png"
    {:get (fn [_]
            {:status 200
             :headers {"Content-Type" "image/png"
                       "Cache-Control" "public, max-age=31536000, immutable"}
             :body (io/input-stream (io/resource "static/clojure-land-og-image.png"))})}]
   ["/_status" {:get (constantly {:status 204})}]])
