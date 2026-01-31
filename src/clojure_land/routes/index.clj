(ns clojure-land.routes.index
  (:require [camel-snake-kebab.core :as csk]
            [clojure-land.icons :as icons]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [dev.onionpancakes.chassis.core :as chassis]
            [malli.core :as m]
            [malli.transform :as mt]
            [reitit.ring]
            [taoensso.telemere] ;; setup logging side-effects
            [zodiac.core :as z]
            [zodiac.ext.assets :as z.assets]
            [zodiac.ext.sql :as z.sql]))

(def json-ld
  "JSON-LD structured data for SEO (rendered once at startup)"
  (json/write-str
   {"@context" "https://schema.org"
    "@type" "WebSite"
    "name" "Clojure Land"
    "description" "Search and discover open source Clojure and ClojureScript libraries"
    "url" "https://clojure.land"
    "potentialAction" {"@type" "SearchAction"
                       "target" {"@type" "EntryPoint"
                                 "urlTemplate" "https://clojure.land/?q={search_term_string}"}
                       "query-input" "required name=search_term_string"}}))

(defn search-input [q]
  [:input {:type "text",
           :id "q"
           :name "q",
           :value q
           :class "block w-full rounded-md bg-white py-1.5 pr-3 pl-3 lg:pl-10 text-base text-gray-900 outline-1 outline-gray-300 focus:outline-offset-0 focus:outline-2 placeholder:text-gray-500 focus:outline-blue-600 sm:pl-9 sm:text-sm/6 border-0"
           :placeholder "Search for projects..."
           :autofocus true
           :hx-get "/"
           :hx-replace-url "true"
           :hx-trigger "input changed delay:250ms, keyup[key=='Enter']"
            ;; prevent the swap from undoing input if the user if typing fast
           :hx-preserve true}])

(defn sort-select [value]
  [:div {:class "flex justify-end items-center gap-2 col-span-6 md:col-span-4 md:col-start-2"
         :id "sort-select"}
   (icons/bars-arrow-down)
   [:select {:id "sort"
             :name "sort"
             :value (if value (name value) "date")
             :class "rounded-md outline-1 outline-gray-300 border-0 text-sm/5 min-w-42"
             :hx-get "/"
             :hx-push-url "true"
             :hx-trigger "change"
             :hx-preserve true}
    [:option {:value "date" :selected (= value :date)} "Last updated"]
    [:option {:value "name" :selected (= value :name)} "Name"]
    [:option {:value "popularity" :selected (= value :popularity)} "Popularity"]
    [:option {:value "stars" :selected (= value :stars)} "Stars"]
    [:option {:value "downloads" :selected (= value :downloads)} "Downloads/day"]]])

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
    [:span {:class (str "rounded-lg text-white text-xs font-bold px-2 py-1 text-nowrap lowercase hover:opacity-80 "
                        bg-color)}
     platform]))

(defn tag-chip [category]
  [:span {:class "rounded-lg text-slate-600 text-xs border-2 border-slate-600 font-bold px-2 py-1 text-nowrap lowercase hover:bg-slate-200"}

   category])

(defn filter-bar [& {:keys [platforms tags]}]
  (let [closable-chip (fn [name value chip]
                        ;; TODO: Instead of messing with vals could we use alpine to
                        ;; remove this button or a hidden input and then submit the form?
                        [:button {:type "button"
                                  :class "cursor-pointer"
                                  :hx-get "/"
                                  :hx-push-url "true"
                                  :hx-include "inherit"
                                  :hx-on:click (format "htmx.remove(htmx.find('input[value=\"%s\"]')); " value)}
                         [:div {:class "flex flex-row items-center gap-1"}
                          [:input {:type "hidden"
                                   :name name
                                   :value value}]
                          chip
                          (chassis/raw "&times;")]])]
    [:div {:id "filter-bar"
           :class "flex flex-row gap-4 col-span-6 md:col-span-4 mx-6 md:mx-2 md:col-start-2 mt-4"}
     (mapv #(closable-chip "platforms" % (platform-chip %)) platforms)
     (mapv #(closable-chip "tags" % (tag-chip %)) tags)]))

(defn sort-platforms-keyfn [p]
  (get {"clj" 0
        "cljs" 1
        "babashka" 2}
       p))

(def grid-classes "col-span-10 col-start-2 md:col-span-10 md:col-start-2 xl:col-span-6 xl:col-start-4")

(defn project-list-item [{:project/keys [archived description downloads-per-day last-pushed-at
                                         latest-release-date name platforms popularity-score
                                         latest-version group-id artifact-id repo-url repository
                                         stars tags url]}
                         & {:keys [attrs]}]
  (let [clojars? (and group-id artifact-id
                      (or (nil? repository) (= repository "clojars")))
        effective-dpd (or downloads-per-day
                          (when clojars? 0.0))
        popover-data (cond-> {}
                       stars (assoc :stars stars)
                       effective-dpd (assoc :downloadsPerDay effective-dpd)
                       latest-release-date (assoc :releaseDate (.toInstant latest-release-date))
                       last-pushed-at (assoc :lastUpdated (.toInstant last-pushed-at))
                       popularity-score (assoc :popularity popularity-score))]
    [:li (merge {:class [grid-classes "relative flex justify-between gap-x-6 py-4"]
                 :data-popover (json/write-str popover-data)}
                attrs)
     [:div {:class "flex flex-row w-full"}
      [:div {:class "flex flex-col gap-2 w-full"}
       [:div {:class "flex flex-row flex-wrap lg:gap-4 items-start md:items-center justify-between"}
        [:span {:class "w-full md:w-auto flex flex-row items-center justify-between gap-2"}
         [:span
          [:a {:class "font-bold text-2xl hover:underline" :href url} name]
          (when archived
            [:span {:title "archived"} (icons/lock)])]
         [:button {:class "popover-trigger text-gray-400 hover:text-gray-600 cursor-pointer"
                   :data-popover (json/write-str popover-data)
                   :aria-label "Show project info"}
          (icons/info)]]
        (when (and artifact-id
                   (or (nil? repository)
                       (= repository :clojars)))
          [:a {:class "inline-block hover:underline"
               :href (str "https://clojars.org/" group-id "/" artifact-id)}
           [:div {:class "flex flex-row flex-wrap md:gap-2 items-center  "}
            [:span {:class "hidden lg:inline text-black/50"} "[ "]
            [:span {:class "w-full md:w-auto"} (str group-id "/" artifact-id)]
            [:span {:class "hidden lg:inline"} " "]
            [:span latest-version]
            [:span {:class "text-black/50 hidden lg:inline"} " ]"]]])]
       [:span {:class "text-lg text-neutral-800 pb-4"} description]
       [:div {:class "flex flex-col md:flex-row justify-between gap-4"}
        [:div {:class "flex flex-row gap-2 flex-wrap"}
         (mapv (fn [platform]
                 [:a {:class "cursor-pointer"
                      :hx-get "/"
                      :hx-push-url "true"
                      :hx-include "inherit"
                      :hx-on:htmx:config-request (format "appendPlatformParameter(event, '%s')" platform)}
                  (platform-chip platform)])
               ;; override sort order of platforms
               (some->> (.getArray (or platforms (array-map)))
                        (sort-by sort-platforms-keyfn)))]
        [:div {:class "flex flex-row gap-2 flex-wrap"}
         (mapv (fn [tag]
                 [:a {:class "cursor-pointer"
                      :hx-get "/"
                      :hx-push-url "true"
                      :hx-include "inherit"
                      :hx-on:htmx:config-request (format "appendTagParameter(event, '%s')" tag)}
                  (tag-chip tag)])
               (some->> (.getArray tags) (take 4) (sort)))]]]]]))

(defn project-list [projects & {:keys [next-page]}]
  (let [num-projects (count projects)]
    [:ul {:id "project-list"
          :class "grid grid-cols-12 gap-2 divide-y divide-gray-100"}
     (vec (map-indexed (fn [idx p]
                         (let [attrs (when (and (= idx (- num-projects 2))
                                                (> num-projects 10))
                                       ;; Load next page on revealed
                                       {:hx-get "/"
                                        ;; Override hx-target, hx-select, etc from <main>
                                        :hx-target "#project-list"
                                        :hx-select "#project-list li"
                                        :hx-swap "beforeend"
                                        :hx-include "inherit"
                                        :hx-vals (js {"page" next-page})
                                        :hx-trigger "revealed"})]
                           (project-list-item p :attrs attrs)))
                       projects))]))

(defn content [& {:keys [q platforms tags projects next-page sort]}]
  [:main {:id "content"
          :hx-include "select#sort, input#q, input[name='tags'], input[name='platforms']"
          :hx-select "#project-list"
          :hx-select-oob "#filter-bar,#sort-select"
          :hx-swap "outerHTML"
          :hx-target "#project-list"}
   [:div {:id "form-container"
          :class "grid grid-cols-12"}
    [:form {:id "form"
            :class [grid-classes "w-full"]}
     (search-input q)
     (filter-bar :platforms platforms :tags tags)
     (sort-select sort)]]
   (project-list projects :next-page next-page)])

(defn projects-stmt [& {:keys [q platforms tags sort]}]
  (let [platforms-conds (mapv #(vector :array_contains :platforms %) platforms)
        tags-conds (mapv #(vector :array_contains :tags %) tags)
        order-by (case sort
                   :name [[[:lower :name]]]
                   :date [[:last-pushed-at :desc]]
                   :popularity [[:popularity-score :desc :nulls-last]]
                   :stars [[:stars :desc :nulls-last]]
                   :downloads [[:downloads-per-day :desc :nulls-last]]
                   [[:last-pushed-at :desc]])]
    (cond-> {:select [:*]
             :from :project
             :where [:and true]
             :order-by order-by}
      (seq q)
      (update :where #(conj % [:or
                               [:ilike :name (str "%" (str/lower-case q) "%")]
                               [:ilike :description (str "%" (str/lower-case q) "%")]]))
      (seq platforms-conds)
      (update :where #(into % platforms-conds))

      (seq tags-conds)
      (update :where #(into % tags-conds)))))

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
   [:sort {:decode/params keyword} [:enum :date :name :popularity :stars :downloads]]
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

(defn handler [{:keys [headers params ::z/context] :as _request}]
  (let [{::z.assets/keys [assets]
         ::z.sql/keys [db]} context
        hx-request? (and (= (get headers "hx-request") "true")
                         (not= (get headers "hx-history-restore-request") "true"))
        {:keys [q page page-size platforms sort tags]} (m/decode QueryParams params params-transformer)
        stmt (projects-stmt :q q
                            :platforms platforms
                            :tags tags
                            :sort sort)
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
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]

        ;; Primary Meta Tags
        [:title "Clojure Land - Search & Discover Open Source Clojure Libraries"]
        [:meta {:name "title" :content "Clojure Land - Search & Discover Open Source Clojure Libraries"}]
        [:meta {:name "description" :content "Browse and search open source Clojure and ClojureScript libraries. Find tools for web development, data processing, testing, databases, and more. Regularly updated with GitHub stars and activity."}]
        [:meta {:name "keywords" :content "Clojure, ClojureScript, libraries, open source, JVM, functional programming, Clojure packages, Clojure tools"}]
        [:meta {:name "author" :content "Clojure Land"}]
        [:meta {:name "robots" :content "index, follow"}]

        ;; Canonical URL
        [:link {:rel "canonical" :href "https://clojure.land/"}]

        ;; Open Graph / Facebook
        [:meta {:property "og:type" :content "website"}]
        [:meta {:property "og:url" :content "https://clojure.land/"}]
        [:meta {:property "og:title" :content "Clojure Land - Search & Discover Open Source Clojure Libraries"}]
        [:meta {:property "og:description" :content "Browse and search open source Clojure and ClojureScript libraries. Find tools for web development, data processing, testing, and more."}]
        [:meta {:property "og:image" :content "https://clojure.land/assets/clojure-land-og-image.png"}]
        [:meta {:property "og:site_name" :content "Clojure Land"}]

        ;; Twitter
        [:meta {:name "twitter:card" :content "summary_large_image"}]
        [:meta {:name "twitter:url" :content "https://clojure.land/"}]
        [:meta {:name "twitter:title" :content "Clojure Land - Search & Discover Open Source Clojure Libraries"}]
        [:meta {:name "twitter:description" :content "Browse and search open source Clojure and ClojureScript libraries. Find tools for web development, data processing, testing, and more."}]
        [:meta {:name "twitter:image" :content "https://clojure.land/assets/clojure-land-og-image.png"}]

        ;; Favicon
        [:link {:rel "icon" :type "image/x-icon" :href "/favicon.ico"}]
        [:link {:rel "apple-touch-icon" :sizes "180x180" :href "/apple-touch-icon.png"}]

        ;; Theme Color
        [:meta {:name "theme-color" :content "#5881d8"}]

        [:link {:rel "stylesheet" :href (assets "main.css")}]
        [:script {:src (assets "main.ts")}]
        [:script {:async true
                  :data-collect-dnt "true"
                  :src "https://scripts.simpleanalyticscdn.com/latest.js"}]

        ;; JSON-LD structured data for SEO
        [:script {:type "application/ld+json"}
         (chassis/raw json-ld)]]
       [:body
        [:div {:id "project-popover"}]
        [:template {:id "popover-template"}
         [:div {:class "popover-row" :data-field "stars"}
          (icons/star) [:span {:data-value ""}]]
         [:div {:class "popover-row" :data-field "downloadsPerDay"}
          (icons/download) [:span {:data-value ""}]]
         [:div {:class "popover-row" :data-field "releaseDate"}
          (icons/package) [:span {:data-value ""}]]
         [:div {:class "popover-row" :data-field "lastUpdated"}
          (icons/refresh-cw) [:span {:data-value ""}]]
         #_[:div {:class "popover-row" :data-field "popularity"}
            (icons/trending-up) [:span {:data-value ""}]]]
        [:div {:class "absolute -top-4 -right-4 scale-30"}
         [:a {:href "https://github.com/brettatoms/clojure.land"}
          (icons/github)]]
        [:div {:class "flex flex-col gap-8 m-auto"}
         [:a {:class "self-center pt-8 px-4 md:px-0"
              :href "/"}
          [:img {:src (assets "clojure-land-logo-small.jpg")
                 :class "max-h-32"}]]
         (content :q q
                  :tags tags
                  :projects projects
                  :platforms platforms
                  :next-page next-page
                  :sort sort)
         [:div {:class "flex flex-row justify-center items-center mt-32 mb-32"}
          [:img {:src (assets "the-end-4.png")
                 :class "max-h-8"}]]]]]

      ;; Return only the content for htmx requests
      (content :q q
               :tags tags
               :projects projects
               :platforms platforms
               :next-page next-page
               :sort sort))))
