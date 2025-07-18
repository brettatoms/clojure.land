(ns build
  (:require
   [clojure.tools.build.api :as b]))

(def basis (delay (b/create-basis {:project "deps.edn"})))
(def src-dirs ["src" "resources"])
(def class-dir "target/classes")

(defn clean [_]
  (b/delete {:path "target"}))

(defn build-assets [_]
  (b/process {:command-args ["npm" "ci"]})
  (b/process {:command-args ["npx" "vite" "build" "-c" "vite.config.ts"]}))

(defn uber [opts]
  (println "Cleaning build directory...")
  (clean nil)

  (println "Building assets...")
  (build-assets nil)

  (println "Copying files...")
  (b/copy-dir {:src-dirs   src-dirs
               :target-dir class-dir})

  (println "Compiling Clojure...")
  (b/compile-clj {:basis      @basis
                  :ns-compile '[clojure-land.main]
                  :class-dir  class-dir})

  (println "Building Uberjar...")
  (b/uber (merge opts
                 {:basis     @basis
                  :class-dir class-dir
                  :uber-file "target/clojure-land.jar"
                  :main      'clojure-land.main})))
