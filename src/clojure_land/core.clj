(ns clojure-land.core
  (:require [babashka.fs :as fs]
            [camel-snake-kebab.core :as csk]
            [clojure-land.icons :as icons]
            [clojure-land.system :as system]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [malli.core :as m]
            [malli.transform :as mt]
            [taoensso.telemere] ;; setup logging side-effects
            [taoensso.telemere.tools-logging :refer [tools-logging->telemere!]]
            [zodiac.core :as z]
            [zodiac.ext.assets :as z.assets]
            [zodiac.ext.sql :as z.sql]))

;; Send clojure.tools.logging message to telemere
(tools-logging->telemere!)


(defn platform-chip [platform]
  (let [bg-color (case platform
                   "clj" "bg-[#63b132]"
                   "cljs" "bg-[#5881d8]"
                   "bg-black")]
    [:span {:class (str "rounded-lg text-white text-sm font-bold px-2 py-1 text-nowrap lowercase "
                        bg-color)}
     platform]))

(defn tag-chip [category]
  [:span {:class "rounded-lg text-white text-sm bg-slate-600 font-bold px-2 py-1 text-nowrap lowercase"}
   category])

(defn project-list-item [{:project/keys [url name description platforms repo-url stars tags]}
                         & {:keys [attrs]}]
  [:li (merge {:class "relative flex justify-between gap-x-6 py-5 col-span-6 md:col-span-4 mx-6 md:mx-2 md:col-start-2"}
              attrs)
   [:div {:class "flex flex-row w-full"}
    [:div {:class "flex flex-col gap-2 w-full"}
     [:div {:class "flex flex-row gap-4 items-center"}
      [:a {:class "font-bold text-2xl hover:underline" :href url} name]
      (when stars
        [:a {:class "flex flex-row flex-1 justify-end gap-2 hover:underline"
             :href repo-url}
         (icons/star)
         [:span stars]])]
     [:span {:class "text-lg text-neutral-800 pb-4"} description]
     [:div {:class "flex flex-col md:flex-row justify-between gap-4"}
      [:div {:class "flex flex-row gap-2 flex-wrap overflow-hidden"}
       (mapv platform-chip (some->> (.getArray platforms) (sort)))]
      [:div {:class "flex flex-row gap-2 flex-wrap overflow-hidden"}
       (mapv tag-chip (some->> (.getArray tags) (take 4) (sort)))]]]]])

(defn js
  "This function is mostly used for passing a clojure map as a js object in html
  attributes"
  [data]
  (json/write-str data :escape-slash false))

(defn project-list-items [projects & {:keys [next-page q]}]
  (let [num-projects (count projects)]
    (vec (map-indexed (fn [idx p]
                        (let [attrs (when (and (= idx (- num-projects 2))
                                               (> num-projects 10))
                                      {:hx-target "#project-list"
                                       :hx-swap "beforeend"
                                       :hx-vals (js (cond-> {}
                                                      (seq q) (assoc :q q)
                                                      (some? next-page) (assoc :page next-page)))
                                       :hx-get "/"
                                       :hx-trigger "revealed"})]
                          (project-list-item p :attrs attrs)))
                      projects))))

(defn form [params]
  [:form {:class "col-span-6 md:col-span-4 md:col-start-2 px-6 md:px-2 md:pt-2 w-full"}
   [:input {:type "text",
            :name "q",
            :class "block w-full rounded-md bg-white py-1.5 pr-3 pl-10 text-base text-gray-900 outline-1 outline-gray-300 focus:outline-offset-0 focus:outline-2 placeholder:text-gray-500 focus:outline-blue-600 sm:pl-9 sm:text-sm/6 border-0"
            :placeholder "Search for projects..."
            :hx-get "/"
            :hx-target "#project-list"
            :hx-replace-url "true"
            :hx-swap "innerHTML"
            :hx-trigger "input changed delay:500ms, keyup[key=='Enter']"
            :value (:q params)}]])

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

(defn handler [{:keys [headers params ::z/context] :as _request}]
  (let [{::z.assets/keys [assets]
         ::z.sql/keys [db]} context
        hx-request? (= (get headers "hx-request") "true")
        {:keys [q page page-size tags platforms]
         :as params} (m/decode QueryParams params params-transformer)
        offset (* (dec page) page-size)
        stmt (cond-> {:select [:*]
                      :from :project
                      :where [:and true]}
               (seq q)
               (update :where #(conj % [:or
                                        [:ilike :name (str "%" (str/lower-case q) "%")]
                                        [:ilike :description (str "%" (str/lower-case q) "%")]])))
        projects (z.sql/execute! db (assoc stmt
                                           :offset offset
                                           :limit page-size))
        total (z.sql/count db stmt)
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
         [:div {:class "self-center pt-8 px-4 md:px-0"}
          [:img {:src (assets "clojure-land-logo-small.jpg")
                 :class "max-h-32"}]]

         [:div {:class "grid grid-cols-6 "}
          (form params)]
         [:ul {:id "project-list"
               :class "grid grid-cols-6 gap-8 divide-y divide-gray-100 "}
          (project-list-items projects
                              :next-page next-page
                              :q q)]
         [:div {:class "flex flex-row justify-center items-center mt-32 mb-32"}
          [:img {:src (assets "the-end-4.png")
                 :class "max-h-8"}]]]]]

      ;; Return only the project list for htmx requests
      (project-list-items projects
                          :next-page next-page
                          :q q))))

(defn routes []
  [["/" {:get #'handler}]
   ["/_status" {:get (constantly {:status 204})}]])

(defmethod ig/init-key ::zodiac [_ config]
  (let [{:keys [build-assets? reload-per-request? request-context port]} config
        project-root "./"
        assets-ext (z.assets/init {;; We use vite.config.js in the Dockerfile
                                   :config-file (str (fs/path project-root "vite.config.dev.ts"))
                                   :package-json-dir project-root
                                   :manifest-path  "clojure.land/build/.vite/manifest.json"
                                   :build? build-assets?
                                   :asset-resource-path "clojure.land/build/assets"})
        ;; TODO: setup jdbc options to automatically convert org.h2.jdbc.JdbcArray to a seq
        sql-ext (z.sql/init {:spec {:jdbcUrl "jdbc:h2:mem:clojure-land;DB_CLOSE_DELAY=-1"}})]
    (z/start {:extensions [assets-ext sql-ext]
              :reload-per-request? reload-per-request?
              :request-context request-context
              :jetty {:join? false
                      :host "0.0.0.0"
                      :port port}
              :routes #'routes})))

(defmethod ig/halt-key! ::zodiac [_ system]
  (z/stop system))

(defn start! []
  (system/start!))
