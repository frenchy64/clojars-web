(ns clojars.routes.api
  (:require
   [clojars.db :as db]
   [clojars.http-utils :refer [wrap-cors-headers]]
   [clojars.stats :as stats]
   [clojars.verification-db :as verification-db]
   [clojure.set :as set]
   [compojure.core :as compojure :refer [ANY context GET]]
   [compojure.route :refer [not-found]]
   [ring.middleware.format-response :refer [wrap-restful-response]]
   [ring.util.response :as ring.util])
  (:import
   (java.sql
    Timestamp)
   (java.time
    ZonedDateTime)))

(defn get-artifact [db stats group-id artifact-id]
  (if-let [artifact (db/find-jar-artifact db group-id artifact-id)]
    (-> artifact
        (assoc
         :recent_versions (db/recent-versions db group-id artifact-id)
         :downloads (stats/download-count stats group-id artifact-id))
        (update :recent_versions
                (fn [versions]
                  (map (fn [version]
                         (assoc version
                                :downloads (stats/download-count stats group-id artifact-id (:version version))))
                       versions)))
        (assoc :dependencies
               (->> (db/find-dependencies db group-id artifact-id (:latest_version artifact))
                    (map #(-> %
                              (select-keys [:dep_group_name :dep_jar_name :dep_version :dep_scope])
                              (set/rename-keys {:dep_group_name :group_name
                                                :dep_jar_name   :jar_name
                                                :dep_version    :version
                                                :dep_scope      :scope})))))
        (ring.util/response))
    (not-found nil)))

(defn- generate-release-response
  [from-inst releases]
  (let [next-inst (if (seq releases)
                    (Timestamp/.toInstant (:created (last releases)))
                    from-inst)]
    ;; Note: :next is deprecated, and may be removed at some point
    {:next      (format "/api/release-feed?from=%s" next-inst)
     :next_from (str next-inst)
     :releases  (mapv (fn [{:keys [created description group_name jar_name version]}]
                        {:artifact_id jar_name
                         :description description
                         :group_id    group_name
                         :released_at created
                         :version     version})
                      releases)}))

;; public only for testing
(def page-size 500)

(defn- get-release-feed
  [db from-str]
  (if-some [from-inst (try
                        (-> (ZonedDateTime/parse from-str)
                            (.toInstant))
                        (catch Exception _
                          nil))]
    (let [releases (db/version-feed db from-inst page-size)]
      (ring.util/response
       (generate-release-response from-inst releases)))
    (ring.util/bad-request
     {:message "Invalid from param. It should be in the format of yyyy-MM-ddTHH:mm:ssZ' or yyyy-MM-ddTHH:mm:ss.SSSZ."
      :from from-str})))

(defn- get-verification-status
  "Get verification status for a specific jar version."
  [db group-id artifact-id version]
  (if-let [verification (verification-db/find-jar-verification db group-id artifact-id version)]
    (ring.util/response
     (-> verification
         (select-keys [:verification_status :verification_method :repo_url
                       :commit_sha :commit_tag :attestation_url
                       :reproducibility_script_url :verification_notes :verified_at])))
    (not-found nil)))

(defn- get-verification-metrics
  "Get verification metrics for all versions of a jar."
  [db group-id artifact-id]
  (if (db/jar-exists db group-id artifact-id)
    (ring.util/response
     (verification-db/verification-metrics db group-id artifact-id))
    (not-found nil)))

(defn- get-all-verifications
  "Get all verification records for a jar."
  [db group-id artifact-id]
  (if (db/jar-exists db group-id artifact-id)
    (let [verifications (verification-db/find-jar-verifications db group-id artifact-id)]
      (ring.util/response
       {:verifications (mapv #(select-keys % [:version :verification_status
                                               :verification_method :repo_url
                                               :commit_sha :commit_tag
                                               :attestation_url
                                               :reproducibility_script_url
                                               :verified_at])
                             verifications)}))
    (not-found nil)))

(defn handler [db stats]
  (compojure/routes
   (context "/api" []
            (GET ["/groups/:group-id" :group-id #"[^/]+"] [group-id]
                 (if-let [jars (seq (db/find-jars-information db group-id))]
                   (ring.util/response
                    (map (fn [jar]
                           (assoc jar
                                  :downloads (stats/download-count stats group-id (:jar_name jar))))
                         jars))
                   (not-found nil)))
            (GET ["/artifacts/:artifact-id" :artifact-id #"[^/]+"] [artifact-id]
                 (get-artifact db stats artifact-id artifact-id))
            (GET ["/artifacts/:group-id/:artifact-id"
                  :group-id #"[^/]+"
                  :artifact-id #"[^/]+"] [group-id artifact-id]
                 (get-artifact db stats group-id artifact-id))
            (GET ["/artifacts/:group-id/:artifact-id/:version/verification"
                  :group-id #"[^/]+"
                  :artifact-id #"[^/]+"
                  :version #"[^/]+"] [group-id artifact-id version]
                 (get-verification-status db group-id artifact-id version))
            (GET ["/artifacts/:group-id/:artifact-id/verification/metrics"
                  :group-id #"[^/]+"
                  :artifact-id #"[^/]+"] [group-id artifact-id]
                 (get-verification-metrics db group-id artifact-id))
            (GET ["/artifacts/:group-id/:artifact-id/verification/all"
                  :group-id #"[^/]+"
                  :artifact-id #"[^/]+"] [group-id artifact-id]
                 (get-all-verifications db group-id artifact-id))
            (GET ["/release-feed"] [from]
                 (get-release-feed db from))
            (GET "/users/:username" [username]
                 (if-let [groups (seq (db/find-groupnames db username))]
                   (ring.util/response {:groups groups})
                   (not-found nil)))
            (ANY "*" _
                 (not-found nil)))))

(defn routes [db stats]
  (-> (handler db stats)
      (wrap-cors-headers)
      (wrap-restful-response :formats [:json :edn :yaml :transit-json]
                             :format-options {:json {:date-format "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"}})))
