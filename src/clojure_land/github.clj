(ns clojure-land.github
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hato.client :as hc]
            [integrant.core :as ig]))

(def base-url "https://api.github.com")

(defprotocol GitHubClientProtocol
  (get-repo [_ name]))

(defn request-factory
  "Return a function to make requests to the OpenAI API with sensible defaults."
  [api-token]
  (fn [path options]
    (hc/request (merge {:url (str base-url path)
                        :method :get
                        :as :json
                        :oauth-token api-token}
                       options))))

(deftype GitHubClient [request]
  GitHubClientProtocol
  (get-repo [_ name]
    (-> (request (str "/repos/" name) {})
        :body
        (json/read-str :key-fn keyword))))

(defmethod ig/init-key ::client [_ {:keys [api-token]}]
  (->GitHubClient (request-factory api-token)))

(defn update-project-from-repo [github-client {:keys [repo-url] :as project}]
  (let [[_ repo-name] (re-matches #"^https?://github.com/(.*?)/?$" repo-url)
        _ (when-not repo-name (throw (ex-info "Could not get repo name for project" {:project project})))
        repo-name (str/lower-case repo-name)
        {:keys [archived description stargazers_count]} (.get-repo github-client repo-name)]
    (cond-> project
      (empty? (:description project))
      (assoc :description description)

      :always
      (assoc :stars stargazers_count
             :archived archived))))
