(ns wcdw.authorization.database
  "Functions to bootstrap a database"
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]))

(def schema
  [;;  Roles
   {:db/id          #db/id[:db.part/db]
    :db/ident       :authorization.role/id
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db.install/_attribute :db.part/db}

   {:db/id          #db/id[:db.part/db]
    :db/ident       :authorization.role/child
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db.install/_attribute :db.part/db}

   {:db/id #db/id[:db.part/db]
    :db/ident :db.part/roles
    :db.install/_partition :db.part/db}

  ;; Permissions
   {:db/id          #db/id[:db.part/db]
    :db/ident       :authorization.permission/role
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}

   {:db/id          #db/id[:db.part/db]
    :db/ident       :authorization.permission/mode
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}

   {:db/id          #db/id[:db.part/db]
    :db/ident       :authorization.permission/resourceId
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}

   {:db/id #db/id[:db.part/db]
    :db/ident :db.part/permissions
    :db.install/_partition :db.part/db}])

(defn install-schema
  [conn]
  (d/transact conn schema))
