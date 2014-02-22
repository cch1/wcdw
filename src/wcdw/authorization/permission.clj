(ns wcdw.authorization.permission
  "Authorization Permission model"
  (:require [clojure.tools.logging :as log]
            [clojure.set :as set]
            [datomic.api :as d]))

(let [schema [{:db/id          #db/id[:db.part/db]
               :db/ident       :authorization.permission/role
               :db/valueType   :db.type/ref
               :db/cardinality :db.cardinality/one
               :db.install/_attribute :db.part/db}
              {:db/id          #db/id[:db.part/db]
               :db/ident       :authorization.permission/mode
               :db/valueType   :db.type/ref
               :db/cardinality :db.cardinality/many
               :db.install/_attribute :db.part/db}
              {:db/id          #db/id[:db.part/db]
               :db/ident       :authorization.permission/resource
               :db/valueType   :db.type/ref
               :db/cardinality :db.cardinality/one
               :db.install/_attribute :db.part/db}
              {:db/id          #db/id[:db.part/db]
               :db/ident       :authorization.resource/name
               :db/valueType   :db.type/keyword
               :db/unique      :db.unique/value
               :db/cardinality :db.cardinality/one
               :db.install/_attribute :db.part/db}
              {:db/id          #db/id[:db.part/db]
               :db/ident       :authorization.mode/name
               :db/valueType   :db.type/keyword
               :db/unique      :db.unique/value
               :db/cardinality :db.cardinality/one
               :db.install/_attribute :db.part/db}
              ;; Partition
              {:db/id #db/id[:db.part/db]
               :db/ident :db.part/permissions
               :db.install/_partition :db.part/db}
              ]]
  (defn initialize!
    "Install schema and load seed data"
    [conn]
    (d/transact conn schema)))

(defn create-resource [conn r]
  (d/transact conn [{:db/id #db/id[:db.part/permissions -1] :authorization.resource/name r}]))

(defn create-mode [conn m]
  (d/transact conn [{:db/id #db/id[:db.part/permissions -1] :authorization.mode/name m}]))

(defn permissions
  "Return a seq of all defined permissions"
  [db]
  (map first (d/q '[:find ?v :where [_ :authorization.permission/mode ?v]] db)))

(def rules
  '[])

(defn grant
  "Grant to the given role permission to access the given resource in the given mode"
  [conn role mode resource]
  (d/transact conn [{:db/id #db/id[:db.part/permissions -1]
                     :authorization.permission/role {:db/id [:authorization.role/id role]}
                     :authorization.permission/mode {:db/id [:authorization.mode/name mode]}
                     :authorization.permission/resource {:db/id [:authorization.resource/name resource]}}]))

(defn revoke
  "Revoke from the given role permission to access the given resource in the given mode"
  [conn role mode resource]
  )

(defn list-perms
  "List permissions a role has for a given resource"
  [db role resource]
  (d/q '[:find ?p :in $ ?role ?resource :where
         [?r :authorization.role/id ?role]
         [?p :authorization.permission/role ?r]
         [?re :authorization.resource/name ?resource]
         [?p :authorization.permission/resource ?re]] db role resource))

(defn unrooted?
  "Does the graph contain roles not descendants of the root?"
  [db]
  (d/q '[:find ?unrooted :in $ % :where ]))

(defn permissions [db]
  (d/q '[:find ?e
         :in $
         :where [?e :authorization.permission/mode ?v]]
       db))
