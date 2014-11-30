(ns wcdw.authorization.permission
  "Authorization Permission model"
  (:require [clojure.tools.logging :as log]
            [clojure.set :as set]
            [datomic.api :as d]))

;; Issues to resolve
;; 1. Should modes be idents or domain-modelled entities with db/unique set to db.unique/identity?

(let [schema [{:db/id          #db/id[:db.part/db]
               :db/ident       :authorization.permission/role
               :db/valueType   :db.type/ref
               :db/cardinality :db.cardinality/one
               :db.install/_attribute :db.part/db}
              {:db/id          #db/id[:db.part/db]
               :db/ident       :authorization.permission/resource
               :db/valueType   :db.type/ref
               :db/cardinality :db.cardinality/one
               :db.install/_attribute :db.part/db}
              {:db/id          #db/id[:db.part/db]
               :db/ident       :authorization.permission/mode
               :db/valueType   :db.type/ref
               :db/cardinality :db.cardinality/many
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

(defn create-mode [conn m]
  (d/transact conn [{:db/id #db/id[:db.part/permissions] :db/ident m}]))

(defn permissions
  "Return a seq of all defined permissions"
  [db]
  (set (d/q '{:find [?role ?mode ?resource]
              :where [[?e :authorization.permission/role ?role]
                      [?e :authorization.permission/mode ?mode]
                      [?e :authorization.permission/resource ?resource]]}
            db)))

(def rules
  '[])

(defn grant
  "Grant to the given role permission to access the given resource in the given mode"
  [conn role mode resource]
  (d/transact conn [{:db/id #db/id[:db.part/permissions]
                     :authorization.permission/mode mode
                     :authorization.permission/role role
                     :authorization.permission/resource resource}]))

(defn revoke
  "Revoke from the given role permission to access the given resource in the given mode"
  [conn role mode resource]
  (let [db (d/db conn)
        permission (d/q '{:find [?e .]
                          :in [$ ?role ?resource]
                          :where [[?e :authorization.permission/role ?role]
                                  [?e :authorization.permission/resource ?resource]]}
                              db role resource)]
    (d/transact conn [[:db/retract permission :authorization.permission/mode mode]])))

(defn permitted?
  [db role mode resource]
  (let [role (d/entid db role)
        resource (d/entid db resource)
        mode (d/entid db mode)]
    (when (and role resource mode)
      (d/q '{:find [?e .]
                    :in [$ ?role ?mode ?resource]
                    :where [[?e :authorization.permission/resource ?resource]
                            [?e :authorization.permission/role ?role]
                            [?e :authorization.permission/mode ?mode]]}
                  db role mode resource))))
