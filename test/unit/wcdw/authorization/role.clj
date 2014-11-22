(ns unit.wcdw.authorization.role
  "Authorization Role model unit tests"
  (:refer-clojure :exclude [ancestors descendants parents])
  (:require [wcdw.authorization.role :refer :all]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [midje.sweet :refer :all]
            [midje.checking.core :refer [extended-=]]))

;; FIXME: Datomic transaction return map-like associations whose values are *not* suitable for the eager
;; evaluation that midje applies to the LHS of facts.  Reference: https://github.com/marick/Midje/issues/269

(defchecker tx-data
  [expected]
  (checker [actual]
           (extended-= (-> actual deref :tx-data vec) expected)))

(def uri "datomic:mem://0")

(def fixtures
  [{:db/id #db/id[:db.part/roles -1]
    :authorization.role/id :u0
    :authorization.role/_child [:authorization.role/id :root]}
   {:db/id #db/id[:db.part/roles -2]
    :authorization.role/id :u1
    :authorization.role/_child [:authorization.role/id :root]}
   {:db/id #db/id[:db.part/roles -3]
    :authorization.role/id :u00
    :authorization.role/_child #db/id[:db.part/roles -1]
    :db/doc "Child of u0"}
   {:db/id #db/id[:db.part/roles -4]
    :authorization.role/id :u000
    :authorization.role/_child #db/id[:db.part/roles -3]
    :db/doc "Grandchild of u0, child of u00"}
   {:db/id #db/id[:db.part/roles -5]
    :authorization.role/id :u00x
    :authorization.role/_child #db/id[:db.part/roles -3]
    :db/doc "Child of u00 and u0"}
   ;; Wire up second parent of :u00x -not possible with map representation above due to duplicate keys
   [:db/add #db/id[:db.part/roles -1] :authorization.role/child #db/id[:db.part/roles -5]]])

(namespace-state-changes [(around :facts (do (d/delete-database uri)
                                             (d/create-database uri)
                                             ?form
                                             (d/delete-database uri)))])

(fact "Can initialize"
  (let [conn (d/connect uri)]
    (:tx-data @(initialize! conn))) => truthy)

;; The following tests interact with the database and expect fixture data to be installed afresh before each test

(namespace-state-changes [(around :facts (do (d/create-database uri)
                                             (let [conn (d/connect uri)]
                                               (initialize! conn)
                                               (d/transact conn fixtures))
                                             ?form
                                             (d/delete-database uri)))])

(fact "Can retrieve all roles"
  (let [db (-> uri d/connect d/db)]
    (roles db) => (just #{:root :u0 :u1 :u00 :u000 :u00x})))

(fact "Can retrieve children of a role"
  (let [db (-> uri d/connect d/db)]
    (children db :u0) => (just #{:u00 :u00x})))

(fact "Can retrieve descendants of a role"
  (let [db (-> uri d/connect d/db)]
    (descendants db :root) => (just #{:root :u1 :u0 :u00 :u000 :u00x})
    (descendants db :u0) => (just #{:u0 :u00 :u000 :u00x})
    (descendants db :u000) => (just #{:u000})))

(fact "Can retrieve parents of a role"
  (let [db (-> uri d/connect d/db)]
    (parents db :u00x) => (just #{:u0 :u00})
    (parents db :u000) => (just #{:u00})))

(fact "Can retrieve ancestors of a role"
  (let [db (-> uri d/connect d/db)]
    (ancestors db :u00) => (just #{:root :u0 :u00})
    (ancestors db :u000) => (just #{:root :u0 :u00 :u000})))

(future-fact "Unrooted roles are detected"
  (let [db (-> uri d/connect d/db)]
    (unrooted? db) => falsey))

(fact "cycles are detected"
  (let [db (-> uri d/connect d/db)]
    (cyclic? db) => falsey)
  (let [ida (d/tempid :db.part/roles)
        idb (d/tempid :db.part/roles)
        trx [{:db/id ida
              :authorization.role/id :ua
              :authorization.role/_child idb}
             {:db/id idb
              :authorization.role/id :ua
              :authorization.role/_child ida}]
        db (-> (d/connect uri) d/db (d/with trx) :db-after)]
    (cyclic? db) => truthy))

(fact "Can create a role"
  (let [conn (-> uri d/connect)]
    (create conn :root :u2) => (tx-data (n-of (partial instance? datomic.db.Datum) 3))
    (children (d/db conn) :root) => (contains #{:u2})
    (roles (d/db conn)) => (contains #{:root :u2})))

(fact "Can assign a role to a parent"
  (let [conn (-> uri d/connect)]
    (assign conn :u0 :u1) => (tx-data (has every? (partial instance? datomic.db.Datum)))
    (children (d/db conn) :u0) => (contains #{:u1})))

(fact "Can extract a role-graph"
  (let [db (-> uri d/connect d/db)]
    (role-graph db) => {:root {:u0 {:u00 {:u000 {} :u00x {}} :u00x {}} :u1 {}}})  )

(fact "Can unassign a child role from a parent"
  (let [conn (-> uri d/connect)]
    (unassign conn :u0 :u00) => (tx-data (has every? (partial instance? datomic.db.Datum)))
    ;; TODO: check that child has root as new parent
    (children (d/db conn) :u0) =not=> (contains #{:u00})))

(fact "Can delete a leaf role"
  (let [conn (-> uri d/connect)]
    (delete conn :u000) => (tx-data (has every? (partial instance? datomic.db.Datum)))
    (roles (d/db conn)) =not=> (contains #{:u000})))

(fact "Can't delete a role that would orphan children"
  (let [conn (-> uri d/connect)]
    (delete conn :u00) => (throws Throwable)
    (roles (d/db conn)) => (contains #{:u00})))
