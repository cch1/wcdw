(ns roomkey.authorization.permission
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
              ;; Partition
              {:db/id #db/id[:db.part/db]
               :db/ident :db.part/permissions
               :db.install/_partition :db.part/db}]]
  (defn initialize!
    "Install schema and load seed data"
    [conn]
    (d/transact conn schema)))

(defn permissions
  "Return a seq of all defined permissions"
  [db]
  (map first (d/q '[:find ?v :where [_ :authorization.permission/ ?v]] db)))

(def rules
  '[])

(defn unrooted?
  "Does the graph contain roles not descendants of the root?"
  [db]
  (d/q '[:find ?unrooted :in $ % :where ]))

(defn create
  "Creates the identified role as a child of the given parent"
  [conn parent-ident ident]
  {:pre [(keyword? ident) (keyword? parent-ident) (not= root ident)]}
  (let [db (d/db conn)]
    ;; TODO: Make this a transaction function
    (assert (ffirst (d/q '[:find ?e :in $ ?ident :where [?e :authorization.role/id ?ident]] db parent-ident))
            "Parent could not be found")
    (d/transact conn [{:db/id #db/id[:db.part/roles -1]
                       :authorization.role/id ident}
                      {:db/id #db/id[:db.part/roles -2]
                       :authorization.role/id parent-ident
                       :authorization.role/child #db/id[:db.part/roles -1]}])))

(defn assign
  "Assign the child role to the parent role"
  [conn parent child]
  (let [db (d/db conn)
        parent-id (ffirst (d/q '[:find ?e :in $ ?ident :where [?e :authorization.role/id ?ident]] db parent))
        child-id (ffirst (d/q '[:find ?e :in $ ?ident :where [?e :authorization.role/id ?ident]] db child))]
    (assert parent-id "Parent could not be found")
    (assert child-id "Child could not be found")
    (d/transact conn [{:db/id parent-id
                       :authorization.role/child child-id}])))

(defn unassign
  "Unassign the child role from the parent role"
  [conn parent child]
  (let [db (d/db conn)
        parent-id (ffirst (d/q '[:find ?e :in $ ?ident :where [?e :authorization.role/id ?ident]] db parent))
        child-id (ffirst (d/q '[:find ?e :in $ ?ident :where [?e :authorization.role/id ?ident]] db child))]
    (assert parent-id "Parent could not be found")
    (assert child-id "Child could not be found")
    (d/transact conn [[:db/retract parent-id :authorization.role/child child-id]])))

(defn delete
  "Deletes the given (childless) role from the database"
  [conn role]
  (let [db (d/db conn)
        id (ffirst (d/q '[:find ?e :in $ ?ident :where [?e :authorization.role/id ?ident]] db role))
        child-ids (map first (d/q '[:find ?e :in $ ?id :where [?id :authorization.role/child ?e]] db id))]
    (assert id "Role not found")
    (assert (not (seq child-ids)) (str "Child roles found " child-ids))
    (d/transact conn [[:db.fn/retractEntity id]])))








(def permissions (atom {}))
(defn permissions [db]
  (d/q '[:find ?e
         :in $
         :where [?e :authorization.permission/mode ?v]]
       db
       ))

(defn grant
  "Grant to the given role permission to access the given resource in the given mode"
  [role mode resource]
  (swap! permissions update-in [resource mode] (fn [roles] (conj (or roles #{}) role))))

(defn revoke
  "Revoke from the given role permission to access the given resource in the given mode"
  [role mode resource]
  (swap! permissions update-in [resource mode] (fn [roles] (disj (or roles #{}) role))))
