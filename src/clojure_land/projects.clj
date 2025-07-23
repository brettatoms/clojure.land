(ns clojure-land.projects
  (:require [clojure.java.io :as io]
            [clojure-land.github :as github]
            [clojure-land.s3 :as s3]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]))

;; TODO: Consider also using the name, website and topics from the github repo. Topics
;; might be problem b/c every project would probably include "Clojure" but I guess we
;; could filter that out.

(defn read-projects-edn []
  (-> (io/resource "clojure.land/projects.edn")
      (slurp)
      (edn/read-string)))

(comment
  (->> (read-projects-edn)
       (filter #(= (:name %) "Biff")))
  ())

(defn fetch-remote-projects [s3-client bucket-name]
  (-> (s3/get-object s3-client {:Bucket bucket-name
                                :Key "project.edn"})

      :Body
      slurp
      edn/read-string))

;; TODO: We need to restart the machines afterwards
;;
;; TODO: If we ever get over >5000 projects then we'll need to chunk these updates to
;; avoid the GitHub API rate limit

(defn sync-remote-projects-edn
  "Update the local projects.edn file with the repo data and store in Tigris/S3."
  ([]
   (sync-remote-projects-edn nil))
  ;; The single arg version is so we can run via a deps.edn alias
  ([_]
   (let [token (System/getenv "GITHUB_API_TOKEN")
         request (github/request-factory token)
         github-client (github/->GitHubClient request)
         projects (->> (read-projects-edn)
                       (remove :ignore)
                       (filter #(some? (:repo-url %)))
                       (filter #(re-matches #".*?github.*?" (:repo-url %)))
                       (pmap (fn [{:keys [key repo-url] :as project}]
                               (log/debug "Updating project: " key)
                               (if (re-matches #".*?github.*?" repo-url)
                                 (github/update-project-from-repo github-client project)
                                 project))))
         s3-client (s3/client {:endpoint-url (System/getenv "AWS_ENDPOINT_URL_S3")
                               :access-key-id (System/getenv "AWS_ACCESS_KEY_ID")
                               :secret-access-key (System/getenv "AWS_SECRET_ACCESS_KEY")})]
     (s3/put-object s3-client {:Bucket (System/getenv "BUCKET_NAME")
                               :Key "project.edn"
                               :Body (pr-str projects)}))))

(comment
  (let [s3-client (s3/client {:endpoint-url (System/getenv "AWS_ENDPOINT_URL_S3")
                              :access-key-id (System/getenv "AWS_ACCESS_KEY_ID")
                              :secret-access-key (System/getenv "AWS_SECRET_ACCESS_KEY")})]
    (fetch-remote-projects s3-client "clojure.land"))
  ())
