(ns clojure-land.core
  (:require [babashka.fs :as fs]
            [camel-snake-kebab.core :as csk]
            [clojure-land.icons :as icons]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hato.client :as hc]
            [integrant.core :as ig]
            [malli.core :as m]
            [malli.transform :as mt]
            [malli.util :as mu]
            [taoensso.telemere] ;; setup logging side-effects
            [taoensso.telemere.tools-logging :refer [tools-logging->telemere!]]
            [zodiac.core :as z]
            [zodiac.ext.assets :as z.assets]
            [zodiac.ext.sql :as z.sql]))

(tools-logging->telemere!)

(defn github-repo! [repo & {:keys [api-key]}]
  (-> (hc/get (str "https://api.github.com/repos/" repo)
              {:as :json
               :oauth-token api-key})
      :body
      (json/read-str :key-fn keyword)))

(defn platform-chip [platform]
  [:span {:class "rounded-lg text-white text-sm bg-slate-600 px-2 py-1 text-nowrap lowercase"}
   platform])

(defn tag-chip [category]
  [:span {:class "rounded-lg text-white text-sm bg-slate-600 px-2 py-1 text-nowrap lowercase"}
   category])

(defn project-list-item [{:project/keys [url name description platforms tags repo-url]}
                         & {:keys [attrs]}]
  [:li (merge {:class "relative flex justify-between gap-x-6 py-5 col-span-4 col-start-2"}
              attrs)
   [:div {:class "flex flex-row"}
    [:div {:class "flex flex-col gap-2"}
     [:div {:class "flex flex-row gap-4"}
      [:span {:class "font-bold text-2xl"} name]
      [:div {:class "flex flex-row gap-2 flex-wrap overflow-hidden"}
       (mapv platform-chip (some->> (.getArray platforms)))]]
     [:span {:class "text-lg text-neutral-600"} description]
     [:div {:class "flex flex-row gap-2 flex-wrap overflow-hidden"}
      (mapv tag-chip (some->> (.getArray tags)))]]]])

(defn project-list-items [projects next-page]
  (vec (map-indexed (fn [idx p]
                      (let [attrs (when (= idx (- (count projects) 2))
                                    {:hx-target "#project-list"
                                     :hx-swap "beforeend"
                                     :hx-get (str "/?page=" next-page)
                                     :hx-trigger "revealed"})]
                        (project-list-item p :attrs attrs)))
                    projects)))

(defn form []
  [:form {:class "flex flex-row col-span-4 col-start-2 w-full gap-4 w-full"}
   [:div {:class "mt-2 flex w-full"}
    [:div {:class "-mr-px grid grow grid-cols-1 focus-within:relative"}
     [:input {:type "text",
              :name "q",
              :class "col-start-1 row-start-1 block w-full rounded-l-md bg-white py-1.5 pr-3 pl-10 text-base text-gray-900 outline-1 -outline-offset-1 outline-gray-300 placeholder:text-gray-400 focus:outline-2 focus:-outline-offset-2 focus:outline-indigo-600 sm:pl-9 sm:text-sm/6"}]]
    [:button {:type "submit",
              :class
              "flex shrink-0 items-center gap-x-1.5 rounded-r-md bg-white px-3 py-2 text-sm font-semibold text-gray-900 outline-1 -outline-offset-1 outline-gray-300 hover:bg-gray-50 focus:relative focus:outline-2 focus:-outline-offset-2 focus:outline-indigo-600"}
     "Search"]]])

(def params-transformer (mt/transformer
                         (mt/key-transformer {:decode (fn [k]
                                                        (cond-> k
                                                          (not (keyword? k))
                                                          csk/->kebab-case-keyword))})
                         {:name :params}
                         mt/strip-extra-keys-transformer
                         mt/default-value-transformer
                         mt/string-transformer))

(def QueryParams
  [:map
   [:q :string]
   [:page [:int {:min 1 :default 1}]]
   [:page-size {:default 20} [:int {:min 10}]]
   [:tags [:sequential :string]]
   [:platforms [:enum :clj :cljs]]])

(comment
  (m/coerce QueryParams {})

  (m/coerce QueryParams {:page 1} params-transformer)
  (m/decode QueryParams {} params-transformer)
  ())

(defn handler [{:keys [headers params ::z/context] :as _request}]
  (let [{::z.assets/keys [assets]
         ::z.sql/keys [db]} context
        hx-request? (= (get headers "hx-request") "true")
        {:keys [q page page-size tags platforms]} (m/decode QueryParams params params-transformer)
        offset (* (dec page) page-size)
        projects (z.sql/execute! db {:select [:*]
                                     :from :project
                                     :offset offset
                                     :limit page-size})
        total (z.sql/count db {:select :* :from :project})
        next-page (when (< offset total)
                    (inc page))]
    (if-not hx-request?
      [:html
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:http-equiv "x-ua-compatible" :content "ie=edge"}]
        [:title "Clojure Land"]
        [:meta {:name "description" :content "A collection of projects, libraries and frameworks for Clojure."}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:script {:src (assets "main.ts")}]
        [:link {:rel "stylesheet" :href (assets "main.css")}]]
       [:body
        [:div {:class "flex flex-col gap-8"}
         [:div {:class "self-center pt-8"}
          [:img {:src (assets "clojure-land-logo-small.jpg")
                 :class "max-h-32"}]]

         [:div {:class "grid grid-cols-6 "}
          (form)]
         [:ul {:id "project-list"
               :class "grid grid-cols-6 gap-8 divide-y divide-gray-100 "}
          (project-list-items projects next-page)]]]]

      ;; Return only the project list for htmx requests
      (project-list-items projects next-page))))

(defn routes []
  [["/" {:get #'handler}]
   ["/_status" {:get (constantly {:status 204})}]])

(defmethod ig/init-key ::zodiac [_ _]
  (let [project-root "./"
        assets-ext (z.assets/init {;; We use vite.config.js in the Dockerfile
                                   :config-file (str (fs/path project-root "vite.config.dev.ts"))
                                   :package-json-dir project-root
                                   :manifest-path  "clojure.land/build/.vite/manifest.json"
                                   ;; build by default unless ZODIAC_ASSETS_BUILD set to "false"
                                   :build? (not= (System/getenv "ZODIAC_ASSETS_BUILD") "false")
                                   :asset-resource-path "clojure.land/build/assets"})
        ;; TODO: setup jdbc options to automatically convert org.h2.jdbc.JdbcArray to a seq
        sql-ext (z.sql/init {:spec {:jdbcUrl "jdbc:h2:mem:clojure-land;DB_CLOSE_DELAY=-1"}})]
    (z/start {:extensions [assets-ext sql-ext]
              :reload-per-request? (= (System/getenv "RELOAD_PER_REQUEST") "true")
              :jetty {:join? false
                      :host "0.0.0.0"
                      :port (or (some-> (System/getenv "PORT") (parse-long))
                                3000)}
              :routes #'routes})))

(defmethod ig/halt-key! ::zodiac [_ system]
  (z/stop system))

(defmethod ig/init-key ::init-db [_ {:keys [zodiac]}]
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
(defn start! []
  (let [config {::zodiac {}
                ::init-db {:zodiac (ig/ref ::zodiac)}}]
    (ig/load-namespaces config)
    (ig/init config)))

(comment
  (do
    (add-tap println)
    (defonce ^:dynamic *system* nil))

  ;; start
  (let [system (start!)]
    (alter-var-root #'*system* (constantly system)))

  ;; stop
  (when *system*
    (alter-var-root #'*system* ig/halt!))
  ())
