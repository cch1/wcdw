(ns wcdw.authorization.role
  "Authorization Role model"
  (:refer-clojure :exclude [ancestors descendants parents])
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]))

(let [schema [{:db/id          #db/id[:db.part/db]
               :db/ident       :authorization.role/id
               :db/valueType   :db.type/keyword
               :db/cardinality :db.cardinality/one
               :db/unique      :db.unique/identity
               :db.install/_attribute :db.part/db}
              {:db/id          #db/id[:db.part/db]
               :db/ident       :authorization.role/children
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
    (d/transact conn schema)))

(defn roles
  "Return a seq of all defined roles"
  [db]
  (d/q '{:find [[?v ...]]
         :where [[_ :authorization.role/id ?v]]} db))

(def rules
  '[[(child ?parent ?child)
     [?parent :authorization.role/children ?child]]
    [(descendant ?ancestor ?descendant)
     (child ?ancestor ?descendant)]
    [(descendant ?ancestor ?descendant)
     (child ?ancestor ?x)
     (descendant ?x ?descendant)]
    [(cyclic ?candidate)
     (descendant ?candidate ?candidate)]
    ;; Descendant or self
    [(descendant+ ?ancestor ?descendant)
     [(identity ?ancestor) ?descendant]]
    [(descendant+ ?ancestor ?descendant)
     (descendant ?ancestor ?descendant)]
    ;; Ancestor or self
    [(ancestor+ ?ancestor ?descendant)
     [(identity ?descendant) ?ancestor]]
    [(ancestor+ ?ancestor ?descendant)
     (descendant ?ancestor ?descendant)]])

(defn cyclic?
  "Does the role graph contain any cycles?"
  [db]
  (seq (d/q '{:find [[?id ...]]
              :in [$ %]
              :where [[?candidate :authorization.role/id ?id]
                      (cyclic ?candidate)]}
            db rules)))

(defn roots
  "Return a list of roles having no parents"
  [db]
  (d/q '{:find [[?id ...]]
         :in [$ %]
         :where [[?e :authorization.role/id ?id]
                 [(datomic.api/entity $ ?e) ?e*]
                 [(-> ?e* :authorization.role/_children empty?)]]}
       db rules))

(defn children
  "Return a list of all child roles of the given parent"
  [db parent-identity]
  (d/q '{:find [[?child-identity ...]]
         :in [$ % ?parent-identity]
         :where [[?parent :authorization.role/id ?parent-identity]
                 (child ?parent ?child)
                 [?child :authorization.role/id ?child-identity]]}
       db rules parent-identity))

(defn descendants
  "Return a list of all descendant roles of the given ancestor"
  [db ancestor-identity]
  (d/q '{:find [[?descendant-identity ...]]
         :in [$ % ?ancestor-identity]
         :where [[?ancestor :authorization.role/id ?ancestor-identity]
                 (descendant+ ?ancestor ?descendant)
                 [?descendant :authorization.role/id ?descendant-identity]]}
       db rules ancestor-identity))

(defn parents
  "Return a list of all parent roles of the given child"
  [db child-identity]
  (d/q '{:find [[?parent-identity ...]]
         :in [$ % ?child-identity]
         :where [[?child :authorization.role/id ?child-identity]
                 (child ?parent ?child)
                 [?parent :authorization.role/id ?parent-identity]]}
       db rules child-identity))

(defn ancestors
  "Return a list of all ancestor roles of the given descendant"
  [db descendant-identity]
  (d/q '{:find [[?ancestor-id ...]]
         :in [$ % ?descendant-identity]
         :where [[?descendant :authorization.role/id ?descendant-identity]
                 (ancestor+ ?ancestor ?descendant)
                 [?ancestor :authorization.role/id ?ancestor-id]]}
       db rules descendant-identity))

(defn role-graph*
  [db start]
  (let [cs (children db start)]
    {start (reduce (fn [acc child] (merge acc (role-graph* db child))) {} cs)}))

(defn role-graph
  [db]
  (let [trees (roots db)]
    (map (partial role-graph* db) trees)))

(defn create
  "Create a role with identity ident and optionally make it a child of parent"
  [conn ident & [parent-ident]]
  {:pre [(keyword? ident) (or (nil? parent-ident) (keyword? parent-ident))]}
  (let [db (d/db conn)
        trx {:db/id #db/id[:db.part/roles -1]
             :authorization.role/id ident}
        trx (if parent-ident (assoc trx :authorization.role/_children [:authorization.role/id parent-ident]) trx)]
    (d/transact conn [trx])))

(defn assign
  "Assign the child role to the parent role"
  [conn parent child]
  (d/transact conn [[:db/add [:authorization.role/id parent]
                     :authorization.role/children [:authorization.role/id child]]]))

(defn reassign
  "Reassign the child role from the current parent to the new parent"
  [conn parent0 parent1 child]
  (d/transact conn [[:db/retract [:authorization.role/id parent0]
                     :authorization.role/children [:authorization.role/id child]]
                    [:db/add [:authorization.role/id parent1]
                     :authorization.role/children [:authorization.role/id child]]]))

(defn unassign
  "Unassign the child role from the parent role"
  [conn parent child]
  (d/transact conn [[:db/retract [:authorization.role/id parent]
                     :authorization.role/children [:authorization.role/id child]]]))

(defn delete
  "Deletes the given (childless) role from the database"
  [conn role]
  (let [db (d/db conn)
        cs (children db role)]
    (assert (not (seq cs)) (str "Child roles found" cs))
    (d/transact conn [[:db.fn/retractEntity [:authorization.role/id role]]])))
