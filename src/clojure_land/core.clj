(ns clojure-land.core
  (:require [babashka.fs :as fs]
            [clojure-land.logging :as log]
            [clojure-land.routes.core :as routes]
            [clojure-land.system :as system]
            [integrant.core :as ig]
            [reitit.ring]
            [zodiac.core :as z]
            [zodiac.ext.assets :as z.assets]
            [zodiac.ext.sql :as z.sql]))

(defn default-handler []
  (reitit.ring/create-default-handler
   {:not-found (fn [{:keys [uri]}]
                 (log/debug (str "404 Not found: " uri))
                 {:status 404, :body "", :headers {}})}))

(defmethod ig/init-key ::zodiac [_ config]
  (let [{:keys [build-assets? jdbc-url reload-per-request? request-context port]} config
        project-root "./"
        assets-ext (z.assets/init {;; We use vite.config.js in the Dockerfile
                                   :config-file (str (fs/path project-root "vite.config.dev.ts"))
                                   :package-json-dir project-root
                                   :manifest-path  "clojure.land/build/.vite/manifest.json"
                                   :build? build-assets?
                                   :asset-resource-path "clojure.land/build/assets"})
        ;; TODO: setup jdbc options to automatically convert org.h2.jdbc.JdbcArray to a seq
        sql-ext (z.sql/init {:spec {:jdbcUrl jdbc-url}})]
    (z/start {:extensions [assets-ext sql-ext]
              :reload-per-request? reload-per-request?
              :default-handler (default-handler)
              :request-context request-context
              :jetty {:join? false
                      :host "0.0.0.0"
                      :port port}
              :routes #'routes/routes})))

(defmethod ig/halt-key! ::zodiac [_ system]
  (z/stop system))

(defn start! []
  (system/start!))
