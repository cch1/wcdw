(ns unit.wcdw.authorization.role
  "Authorization Role model unit tests"
  (:refer-clojure :exclude [ancestors descendants parents])
  (:require [wcdw.authorization.role :refer :all]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [midje.sweet :refer :all]
            [midje.checking.core :refer [extended-=]]))

(def uri "datomic:mem://0")

(def ^:dynamic *conn*)

(namespace-state-changes [(around :facts (do (d/delete-database uri)
                                             (d/create-database uri)
                                             (binding [*conn* (d/connect uri)]
                                               ?form)))])

(fact "Has reasonable schema"
      (:tx-data (d/with (d/db *conn*) schema)) => (has every? (partial instance? datomic.Datom)))

;; The following tests interact with the database and expect fixture data to be installed afresh before each test
;; FIXME: Datomic transaction return map-like associations whose values are *not* suitable for the eager
;; evaluation that midje applies to the LHS of facts.  Reference: https://github.com/marick/Midje/issues/269

(defchecker tx-data
  [expected]
  (checker [actual]
           (extended-= (-> actual deref :tx-data vec) expected)))

(defchecker refers-to-tx-exception
  [expected]
  (checker [actual]
           (try (deref actual) false (catch Throwable e (extended-= (ex-data (.getCause e)) expected)))))

(def fixtures
  [[{:db/id #db/id[:db.part/roles -1000]
     :authorization.role/id :root}
    {:db/id #db/id[:db.part/roles -1]
     :authorization.role/id :u0
     :authorization.role/_children #db/id[:db.part/roles -1000]}
    {:db/id #db/id[:db.part/roles -2]
     :authorization.role/id :u1
     :authorization.role/_children #db/id[:db.part/roles -1000]}
    {:db/id #db/id[:db.part/roles -3]
     :authorization.role/id :u00
     :authorization.role/_children #db/id[:db.part/roles -1]
     :db/doc "Child of u0"}
    {:db/id #db/id[:db.part/roles -4]
     :authorization.role/id :u000
     :authorization.role/_children #db/id[:db.part/roles -3]
     :db/doc "Grandchild of u0, child of u00"}
    {:db/id #db/id[:db.part/roles -5]
     :authorization.role/id :u00x
     :authorization.role/_children #db/id[:db.part/roles -3]
     :db/doc "Child of u00 and u0"}
    ;; Wire up second parent of :u00x -not possible with map representation above due to duplicate keys
    [:db/add #db/id[:db.part/roles -1] :authorization.role/children #db/id[:db.part/roles -5]]]])

(defn install-fixtures [conn txs]
  (doseq [tx txs]
    (d/transact conn tx)))

(namespace-state-changes [(around :facts (do (d/delete-database uri)
                                             (d/create-database uri)
                                             (binding [*conn* (d/connect uri)]
                                               (install-fixtures *conn* (concat [schema] fixtures))
                                               ?form)))])

(fact "Can retrieve all roles"
      (let [db (d/db *conn*)]
        (roles db) => (just #{:root :u0 :u1 :u00 :u000 :u00x})))

(fact "Can retrieve children of a role"
      (let [db (d/db *conn*)]
        (children db :u0) => (just #{:u00 :u00x})))

(fact "Can retrieve descendants of a role"
      (let [db (d/db *conn*)]
        (descendants db :root) => (just #{:root :u1 :u0 :u00 :u000 :u00x})
        (descendants db :u0) => (just #{:u0 :u00 :u000 :u00x})
        (descendants db :u000) => (just #{:u000})))

(fact "Can retrieve parents of a role"
      (let [db (d/db *conn*)]
        (parents db :u00x) => (just #{:u0 :u00})
        (parents db :u000) => (just #{:u00})))

(fact "Can retrieve ancestors of a role"
      (let [db (d/db *conn*)]
        (ancestors db :u00) => (just #{:root :u0 :u00})
        (ancestors db :u000) => (just #{:root :u0 :u00 :u000})))

(fact "Root roles are identified"
      (let [db (d/db *conn*)]
        (roots db) => (just #{:root})))

(fact "cycles are detected"
      (let [db (d/db *conn*)]
        (cyclic? db) => falsey)
      (let [ida (d/tempid :db.part/roles)
            idb (d/tempid :db.part/roles)
            trx [{:db/id ida
                  :authorization.role/id :ua
                  :authorization.role/_children idb}
                 {:db/id idb
                  :authorization.role/id :ua
                  :authorization.role/_children ida}]
            db (-> (d/connect uri) d/db (d/with trx) :db-after)]
        (cyclic? db) => truthy))

(fact "Can extract a role-graph"
      (let [db (d/db *conn*)]
        (role-graph db) => (just #{{:root {:u0 {:u00 {:u000 {} :u00x {}} :u00x {}} :u1 {}}}}))  )

(fact "Can create a root role"
      (create *conn* :administrator) => (tx-data (n-of (partial instance? datomic.db.Datum) 2))
      (roots (d/db *conn*)) => (contains #{:administrator}))

(fact "Can create a subordinate role"
      (create *conn* :u2 :root) => (tx-data (n-of (partial instance? datomic.db.Datum) 3))
      (children (d/db *conn*) :root) => (contains #{:u2})
      (roles (d/db *conn*)) => (contains #{:u2})
      (roles (d/db *conn*)) => (contains #{:root}))

(fact "Can't create a role with an invalid parent"
      (create *conn* :inexistant-role :new-role) => (refers-to-tx-exception {:db/error :db.error/not-an-entity}))

(fact "Can assign a role to a parent"
      (assign *conn* :u0 :u1) => (tx-data (has every? (partial instance? datomic.db.Datum)))
      (children (d/db *conn*) :u0) => (contains #{:u1}))

(fact "Can't assign a role to a parent unless both exist"
      (assign *conn* :inexistant0 :inexistant1) => (refers-to-tx-exception {:db/error :db.error/not-an-entity})
      (assign *conn* :inexistant :u1) => (refers-to-tx-exception {:db/error :db.error/not-an-entity})
      (assign *conn* :u0 :inexistant) => (refers-to-tx-exception {:db/error :db.error/not-an-entity}))

(fact "Can unassign a child role from a parent"
      (unassign *conn* :u0 :u00) => (tx-data (has every? (partial instance? datomic.db.Datum)))
      ;; TODO: check that child has root as new parent
      (children (d/db *conn*) :u0) =not=> (contains #{:u00}))

(fact "Can't unassign a role from a parent unless both exist"
      ;; (unassign *conn* :u1 :u000) =future=> (throws Exception)
      (unassign *conn* :inexistant0 :inexistant1) => (refers-to-tx-exception {:db/error :db.error/not-an-entity})
      (unassign *conn* :inexistant :u1) => (refers-to-tx-exception {:db/error :db.error/not-an-entity})
      (unassign *conn* :u0 :inexistant) => (refers-to-tx-exception {:db/error :db.error/not-an-entity}))

(fact "Can delete a leaf role"
      (delete *conn* :u000) => (tx-data (has every? (partial instance? datomic.db.Datum)))
      (roles (d/db *conn*)) =not=> (contains #{:u000}))

(fact "Can't delete a role that would orphan children"
      (delete *conn* :u00) => (throws java.lang.AssertionError)
      (roles (d/db *conn*)) => (contains #{:u00}))

(fact "Can't delete a role unless it exists"
      (delete *conn* :inexistant) => (refers-to-tx-exception (contains {:db/error anything})))
