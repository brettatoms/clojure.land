(ns clojure-land.core
  (:require [babashka.fs :as fs]
            [dev.onionpancakes.chassis.core :as chassis]
            [camel-snake-kebab.core :as csk]
            [clojure-land.icons :as icons]
            [clojure-land.system :as system]
            [clojure.data.json :as json]
            [clojure.string :as str]
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

(defn js
  "This function is mostly used for passing a clojure map as a js object in html
  attributes"
  [data]
  (json/write-str data :escape-slash false))

(defn platform-chip [platform]
  (let [bg-color (case platform
                   "clj" "bg-[#63b132]"
                   "cljs" "bg-[#5881d8]"
                   "babashka" "bg-[#ed474a]"
                   "bg-black")]
    [:span {:class (str "rounded-lg text-white text-sm font-bold px-2 py-1 text-nowrap lowercase "
                        bg-color)}
     platform]))

(defn tag-chip [category]
  [:span {:class "rounded-lg text-white text-sm bg-slate-600 font-bold px-2 py-1 text-nowrap lowercase"}
   category])

(defn sort-platforms-keyfn [p]
  (get {"clj" 0
        "cljs" 1
        "babashka" 2}
       p))

(defn project-list-item [{:project/keys [url name description platforms repo-url stars tags]}
                         & {:keys [attrs current-vals]}]

  [:li (merge {:class "relative flex justify-between gap-x-6 py-4 col-span-6 md:col-span-4 mx-6 md:mx-2 md:col-start-2"}
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
      [:div {:class "flex flex-row gap-2 flex-wrap"}
       (mapv (fn [platform]
               [:a {:class "cursor-pointer"
                    :hx-get "/"
                    :hx-target "#content"
                    :hx-swap "innerHTML"
                    :hx-push-url "true"
                    :hx-vals (js (update current-vals "platforms" #(conj % platform)))}

                (platform-chip platform)])
             ;; override sort order of platforms
             (some->> (.getArray platforms)
                      (sort-by sort-platforms-keyfn)))]
      [:div {:class "flex flex-row gap-2 flex-wrap"}
       (mapv (fn [tag]
               [:a {:class "cursor-pointer"
                    :hx-get "/"
                    :hx-target "#content"
                    :hx-swap "innerHTML"
                    :hx-push-url "true"
                    :hx-vals (js (update current-vals "tags" #(conj % tag)))}
                (tag-chip tag)])
             (some->> (.getArray tags) (take 4) (sort)))]]]]])

(defn project-list [projects & {:keys [next-page current-vals]}]
  (let [num-projects (count projects)]
    [:ul {:id "project-list"
          :class "grid grid-cols-6 gap-2 divide-y divide-gray-100 mt-6"}
     (vec (map-indexed (fn [idx p]
                         (let [attrs (when (and (= idx (- num-projects 2))
                                                (> num-projects 10))
                                       {:hx-get "/"
                                        :hx-target "#project-list"
                                        :hx-select "#project-list li"
                                        :hx-swap "beforeend"
                                        :hx-vals (js (cond-> current-vals
                                                       (some? next-page) (assoc :page next-page)))
                                        :hx-trigger "revealed"})]
                           (project-list-item p
                                              :attrs attrs
                                              :current-vals current-vals)))
                       projects))]))

(defn search-form [q & {:keys [current-vals]}]
  [:form {:class "col-span-6 md:col-span-4 md:col-start-2 px-6 md:px-2 md:pt-2 w-full"}
   [:input {:type "text",
            :id "search-input"
            :name "q",
            :class "block w-full rounded-md bg-white py-1.5 pr-3 pl-10 text-base text-gray-900 outline-1 outline-gray-300 focus:outline-offset-0 focus:outline-2 placeholder:text-gray-500 focus:outline-blue-600 sm:pl-9 sm:text-sm/6 border-0"
            :placeholder "Search for projects..."
            :autofocus true
            :hx-get "/"
            :hx-target "#content"
            :hx-replace-url "true"
            :hx-trigger "input changed delay:250ms, keyup[key=='Enter']"
            :value q
            :hx-vals (js (dissoc current-vals "q"))}]])

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
   [:tags {:decode/params (fn [v]
                            (cond
                              (string? v) #{v}
                              :else (set v)))}
    [:set :string]]
   [:platforms {:decode/params (fn [v]
                                 (cond
                                   (string? v) #{v}
                                   :else (set v)))}

    [:set [:enum "clj" "cljs" "babashka"]]]])

(defn filter-bar [& {:keys [platforms tags current-vals]}]
  (let [closable-chip (fn [chip vals]
                        [:button {:type "button"
                                  :class "cursor-pointer"
                                  :hx-get "/"
                                  :hx-target "#content"
                                  :hx-swap "innerHTML"
                                  :hx-push-url "true"
                                  :hx-vals (js vals)}
                         [:div {:class "flex flex-row items-center gap-1"}
                          chip
                          (chassis/raw "&times;")]])]
    [:div {:id "filter-bar"
           :class "flex flex-row gap-4 col-span-6 md:col-span-4 mx-6 md:mx-2 md:col-start-2 mt-4"}
     (mapv (fn [platform]
             (closable-chip
              (platform-chip platform)
              (update current-vals "platforms" #(remove (partial = platform) %))))
           platforms)
     (mapv (fn [tag]
             (closable-chip
              (tag-chip tag)
              (update current-vals "tags" #(remove (partial = tag) %))))
           tags)]))

(defn projects-stmt [& {:keys [q platforms tags]}]
  (let [platforms-conds (mapv #(vector :array_contains :platforms %) platforms)
        tags-conds (mapv #(vector :array_contains :tags %) tags)]
    (cond-> {:select [:*]
             :from :project
             :where [:and true]
             :order-by [:name]}
      (seq q)
      (update :where #(conj % [:or
                               [:ilike :name (str "%" (str/lower-case q) "%")]
                               [:ilike :description (str "%" (str/lower-case q) "%")]]))
      (seq platforms-conds)
      (update :where #(into % platforms-conds))

      (seq tags-conds)
      (update :where #(into % tags-conds)))))

(defn content [& {:keys [q platforms tags projects next-page]}]
  (let [current-vals  (cond-> {}
                        q (assoc "q" q)
                        platforms (assoc "platforms" platforms)
                        tags (assoc "tags" tags))]
    [:div {:id "content"}
     [:div {:id "form-container"
            :class "grid grid-cols-6"}
      (search-form q :current-vals current-vals)
      (filter-bar :platforms platforms :tags tags :current-vals current-vals)]
     (project-list projects :current-vals current-vals :next-page next-page)]))

(defn handler [{:keys [headers params ::z/context] :as _request}]
  (let [{::z.assets/keys [assets]
         ::z.sql/keys [db]} context
        hx-request? (= (get headers "hx-request") "true")
        {:keys [q page page-size tags platforms]} (m/decode QueryParams params params-transformer)
        stmt (projects-stmt :q q
                            :platforms platforms
                            :tags tags)
        offset (* (dec page) page-size)
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
        [:meta {:name "description" :content "Discover open-source Clojure projects."}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:script {:src (assets "main.ts")}]
        [:link {:rel "stylesheet" :href (assets "main.css")}]]
       [:body
        [:div {:class "absolute -top-4 -right-4 scale-30"}
         [:a {:href "https://github.com/brettatoms/clojure.land"}
          (icons/github)]]
        [:div {:class "flex flex-col gap-8 m-auto max-w-[1024px]"}
         [:div {:class "self-center pt-8 px-4 md:px-0"}
          [:img {:src (assets "clojure-land-logo-small.jpg")
                 :class "max-h-32"}]]
         [:div {:id "content"}
          (content :q q
                   :tags tags
                   :projects projects
                   :platforms platforms
                   :next-page next-page)]
         [:div {:class "flex flex-row justify-center items-center mt-32 mb-32"}
          [:img {:src (assets "the-end-4.png")
                 :class "max-h-8"}]]]]]

      ;; Return only the content for htmx requests
      (content :q q
               :tags tags
               :projects projects
               :platforms platforms
               :next-page next-page))))

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
