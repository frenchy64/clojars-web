(ns clojars.db.migrate
  (:require
   [clojars.db :as db]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [next.jdbc :as jdbc]
   [next.jdbc.sql :as sql])
  (:import
   (java.io
    File)))

(set! *warn-on-reflection* true)

(defn initial-schema [tx]
  (doseq [^String cmd (map str/trim
                           (-> (str "queries" File/separator "clojars.sql")
                               io/resource
                               slurp
                               (str/split #";\s*")))
          :when (not (.isEmpty cmd))
          :when (not (re-find #"^\s*--" cmd))]
    (db/do-commands tx [cmd])))

;; migrations mechanics

(defn run-and-record [migration tx]
  (println "Running migration:" (:name (meta migration)))
  (migration tx)
  (sql/insert! tx
               :migrations
               {:name       (-> migration (meta) :name (str))
                :created_at (db/get-time)}))

(defn- add-deploy-tokens-table
  [tx]
  (db/do-commands tx
                  [(str "create table deploy_tokens "
                        "(id serial not null primary key,"
                        " user_id integer not null references users(id) on delete cascade,"
                        " name text not null,"
                        " token text unique not null,"
                        " created timestamp not null default current_timestamp,"
                        " updated timestamp not null default current_timestamp,"
                        " disabled boolean not null default false)")]))

(defn- add-last-used-to-deploy-tokens-table
  [tx]
  (db/do-commands tx
                  [(str "alter table deploy_tokens "
                        "add last_used timestamp default null")]))

(defn- add-group-and-jar-to-deploy-tokens-table
  [tx]
  (db/do-commands tx
                  [(str "alter table deploy_tokens "
                        "add group_name text default null,"
                        "add jar_name text default null")]))

(defn- add-mfa-fields-to-users-table
  [tx]
  (db/do-commands tx
                  [(str "alter table users "
                        "add otp_secret_key text default null,"
                        "add otp_recovery_code text default null,"
                        "add otp_active boolean default false")]))

(defn- add-hash-to-deploy-tokens-table
  [tx]
  (db/do-commands tx
                  [(str "alter table deploy_tokens "
                        "add token_hash text default null")]))

(defn- add-group-verifications-table
  [tx]
  (db/do-commands tx
                  [(str "create table group_verifications "
                        "(id serial not null primary key,"
                        "group_name text unique not null,"
                        "verified_by text not null,"
                        "created timestamp not null default current_timestamp)")]))

(defn- add-audit-table
  [tx]
  (db/do-commands tx
                  [(str "create table audit "
                        "(\"user\" text,"
                        "group_name text,"
                        "jar_name text,"
                        "version text,"
                        "message text,"
                        "tag text not null,"
                        "created timestamp not null default current_timestamp)")]))

(defn- add-single-use-to-tokens
  [tx]
  (db/do-commands tx
                  ["create type single_use_status as enum ('no', 'yes', 'used')"
                   "alter table deploy_tokens add single_use single_use_status default 'no'"]))

(defn- add-expires-at-to-tokens
  [tx]
  (db/do-commands tx
                  ["alter table deploy_tokens add expires_at timestamp"]))

(defn- add-send-deploy-emails-to-users
  [tx]
  (db/do-commands tx
                  ["alter table users add send_deploy_emails boolean default false"]))

(defn- add-indexes-to-deps-table
  [tx]
  (db/do-commands tx
                  ["create index deps_idx0 on deps (dep_group_name, dep_jar_name)"
                   "create index deps_idx1 on deps (group_name, jar_name, version)"]))

(defn- add-group-settings-table
  [tx]
  (db/do-commands tx
                  [(str "create table group_settings "
                        "(group_name text primary key,"
                        "require_mfa_to_deploy bool,"
                        "updated timestamp not null default current_timestamp)")]))

(defn- rename-groups-to-permissions
  [tx]
  (db/do-commands
   tx
   ["alter table groups rename to permissions"
    "alter table permissions rename column name to group_name"]))

(defn- add-scope-to-permissions
  [tx]
  (db/do-commands
   tx
   ["alter table permissions add scope text default '*' not null"
    "create index permissions_idx0 on permissions (group_name, scope)"]))

;; This makes the release feed faster
(defn- add-created-index-to-jars-table
  [tx]
  (db/do-commands tx
                  ["create index jars_idx_created on jars (created)"]))

(defn- add-jar-verifications-table
  [tx]
  (db/do-commands tx
                  [(str "create table jar_verifications "
                        "(id serial not null primary key,"
                        " group_name text not null,"
                        " jar_name text not null,"
                        " version text not null,"
                        " verification_status text not null,"
                        " verification_method text,"
                        " repo_url text,"
                        " commit_sha text,"
                        " commit_tag text,"
                        " attestation_url text,"
                        " reproducibility_script_url text,"
                        " verification_notes text,"
                        " verified_at timestamp not null default current_timestamp,"
                        " unique(group_name, jar_name, version))")
                   "create index jar_verifications_idx0 on jar_verifications (group_name, jar_name)"
                   "create index jar_verifications_idx1 on jar_verifications (verification_status)"]))

(defn- add-verification-settings-to-group-settings
  [tx]
  (db/do-commands tx
                  [(str "alter table group_settings "
                        "add column minimum_verification_method text default null, "
                        "add column verification_legacy_provenance bool default false, "
                        "add column verification_last_analyzed timestamp default null")]))

(def migrations
  [#'initial-schema
   #'add-deploy-tokens-table
   #'add-last-used-to-deploy-tokens-table
   #'add-group-and-jar-to-deploy-tokens-table
   #'add-mfa-fields-to-users-table
   #'add-hash-to-deploy-tokens-table
   #'add-group-verifications-table
   #'add-audit-table
   #'add-single-use-to-tokens
   #'add-expires-at-to-tokens
   #'add-send-deploy-emails-to-users
   #'add-indexes-to-deps-table
   #'add-group-settings-table
   #'rename-groups-to-permissions
   #'add-scope-to-permissions
   #'add-created-index-to-jars-table
   #'add-jar-verifications-table
   #'add-verification-settings-to-group-settings])

(defn migrate [db]
  (db/do-commands db
                  [(str "create table if not exists migrations "
                        "(name varchar not null, "
                        "created_at timestamp not null default current_timestamp)")])
  (jdbc/with-transaction [tx db]
    (let [has-run? (into #{}
                         (map :migrations/name)
                         (sql/query tx ["SELECT name FROM migrations"]))]
      (doseq [m migrations
              :when (not (has-run? (str (:name (meta m)))))]
        (run-and-record m tx)))))
