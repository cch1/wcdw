(ns wcdw.authorization.role
  "Authorization Role model"
  (:refer-clojure :exclude [ancestors descendants parents])
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]))

(def ^:private root :root)

(let [schema [{:db/id          #db/id[:db.part/db]
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
              ;; Partition
              {:db/id #db/id[:db.part/db]
               :db/ident :db.part/roles
               :db.install/_partition :db.part/db}]]
  (defn initialize!
    "Install schema and load seed data"
    [conn]
    (d/transact conn schema)
    (d/transact conn [{:db/id #db/id[:db.part/roles]
                       :authorization.role/id root}])))

(defn roles
  "Return a seq of all defined roles"
  [db]
  (map first (d/q '[:find ?v :where [_ :authorization.role/id ?v]] db)))

(def rules
  '[[[child ?parent ?child]
     [?parent :authorization.role/child ?child]]
    [[descendant ?ancestor ?descendant]
     (child ?ancestor ?descendant)]
    [[descendant ?ancestor ?descendant]
     (child ?ancestor ?x)
     (child ?x ?descendant)]
    [[cyclic? ?candidate]
     (descendant ?candidate ?candidate)]
    ;; Descendant or self
    [[descendant+ ?ancestor ?descendant]
     [(identity ?ancestor) ?descendant]]
    [[descendant+ ?ancestor ?descendant]
     (descendant ?ancestor ?descendant)]
    ;; Ancestor or self
    [[ancestor+ ?ancestor ?descendant]
     [(identity ?descendant) ?ancestor]]
    [[ancestor+ ?ancestor ?descendant]
     (descendant ?ancestor ?descendant)]])

(defn cyclic?
  "Does the role graph contain any cycles?"
  [db]
  (seq (map first (d/q '[:find ?id :in $ % :where
                         ;; Why is this next clause needed?
                         [?candidate :authorization.role/id ?id]
                         (cyclic? ?candidate)]
                       db rules))))

(defn unrooted?
  "Does the graph contain roles not descendants of the root?"
  [db]
  (d/q '[:find ?unrooted :in $ % :where ]))

(defn children
  "Return a list of all child roles of the given parent"
  [db parent-identity]
  (map first
       (d/q '[:find ?child-identity :in $ % ?parent-identity :where
              [?parent :authorization.role/id ?parent-identity]
              (child ?parent ?child)
              [?child :authorization.role/id ?child-identity]]
            db rules parent-identity)))

(defn descendants
  "Return a list of all descendant roles of the given ancestor"
  [db ancestor-identity]
  (map first
       (d/q '[:find ?descendant-identity :in $ % ?ancestor-identity :where
              [?ancestor :authorization.role/id ?ancestor-identity]
              (descendant+ ?ancestor ?descendant)
              [?descendant :authorization.role/id ?descendant-identity]]
            db rules ancestor-identity)))

(defn parents
  "Return a list of all parent roles of the given child"
  [db child-identity]
  (map (comp :authorization.role/id (partial d/entity db) first)
       (d/q '[:find ?parent :in $ % ?child-identity :where
              [?child :authorization.role/id ?child-identity]
              (child ?parent ?child)]
            db rules child-identity)))

(defn ancestors
  "Return a list of all ancestor roles of the given descendant"
  [db descendant-identity]
  (map (comp :authorization.role/id (partial d/entity db) first)
       (d/q '[:find ?ancestor :in $ % ?descendant-identity :where
              [?descendant :authorization.role/id ?descendant-identity]
              (ancestor+ ?ancestor ?descendant)]
            db rules descendant-identity)))

(defn role-graph
  [db & [start]]
  (let [start (or start root)
        cs (children db start)]
    {start (reduce (fn [acc child] (merge acc (role-graph db child))) {} cs)}))

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
