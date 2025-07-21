(ns clojure-land.s3
  (:require [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [lambdaisland.uri :as uri]
            [integrant.core :as ig]))

(defn client [{:keys [endpoint-url access-key-id secret-access-key]}]
  (let [{hostname :host
         scheme :scheme} (uri/uri endpoint-url)
        credentials-provider (credentials/basic-credentials-provider
                              {:access-key-id access-key-id
                               :secret-access-key secret-access-key})]
    (aws/client {:api :s3
                 ;; This has to be a valid AWS region per the Cognitect AWS API
                 ;; even though Tigris doesn't use the same region names
                 :region "us-east-1"
                 :credentials-providers credentials-provider
                 :endpoint-override {:protocol (keyword scheme)
                                     :hostname hostname}})))

(defn put-object [client request]
  (aws/invoke client {:op :PutObject
                      :request request}))

(defn get-object [client request]
  (aws/invoke client {:op :GetObject
                      :request request}))

(defmethod ig/init-key ::client [_ config]
  (client config))
