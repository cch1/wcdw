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
               :db/ident       :authorization.mode/id
               :db/valueType   :db.type/keyword
               :db/cardinality :db.cardinality/one
               :db.install/_attribute :db.part/db}
              {:db/id          #db/id[:db.part/db]
               :db/ident       :authorization.resource/id
               :db/valueType   :db.type/keyword
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

(defn init-perm [conn r]
  (let [add [[:db/add #db/id [:db.part/permissions] :db/ident r]]]
    (d/transact conn add)))

(defn permissions
  "Return a seq of all defined permissions"
  [db]
  (map first (d/q '[:find ?v :where [_ :authorization.permission/mode ?v]] db)))

(def rules
  '[])

(defn grant
  "Grant to the given role permission to access the given resource in the given mode"
  [conn role mode resource]
  (d/transact conn [{:db/id #db/id[:db.part/roles -1]
                       :authorization.permission/role role
                       :authorization.permission/mode mode
                       :authorization.permission/resource resource}]))
(defn revoke
  "Revoke from the given role permission to access the given resource in the given mode"
  [conn role mode resource]
  )

(defn unrooted?
  "Does the graph contain roles not descendants of the root?"
  [db]
  (d/q '[:find ?unrooted :in $ % :where ]))

(defn permissions [db]
  (d/q '[:find ?e
         :in $
         :where [?e :authorization.permission/mode ?v]]
       db
       ))



