(ns unit.wcdw.authorization.permission
  "Authorization Permission model unit tests"
  (:require [wcdw.authorization.permission :refer :all]
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

(def role-schema
  [{:db/id          #db/id[:db.part/db]
    :db/ident       :authorization.role/id
    :db/valueType   :db.type/keyword
    :db/unique      :db.unique/value
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}
   {:db/id #db/id[:db.part/db]
    :db/ident :db.part/roles
    :db.install/_partition :db.part/db}])

(def resource-schema
  [{:db/id          #db/id[:db.part/db]
    :db/ident       :authorization.resource/id
    :db/valueType   :db.type/keyword
    :db/unique      :db.unique/value
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}
   {:db/id #db/id[:db.part/db]
    :db/ident :db.part/resources
    :db.install/_partition :db.part/db}])

(def mode-fixtures
  [{:db/id #db/id[:db.part/permissions]
    :db/ident :read}
   {:db/id #db/id[:db.part/permissions]
    :db/ident :delete}])

(def permission-fixtures
     [{:db/id #db/id[:db.part/roles -100]
       :authorization.role/id :role}
      {:db/id #db/id[:db.part/roles -101]
       :authorization.role/id :role1}
      {:db/id #db/id[:db.part/resources -200]
       :authorization.resource/id :resource}
      {:db/id #db/id[:db.part/resources -201]
       :authorization.resource/id :resource1}
      {:db/id #db/id[:db.part/permissions]
       :authorization.permission/role #db/id[:db.part/roles -100]
       :authorization.permission/resource #db/id[:db.part/resources -200]
       :authorization.permission/mode :read}])

(def fixtures [role-schema resource-schema mode-fixtures permission-fixtures])

(defn install-fixtures [conn txs]
  (doseq [tx txs]
    (d/transact conn tx)))

(namespace-state-changes [(around :facts (do (d/delete-database uri)
                                             (d/create-database uri)
                                             (binding [*conn* (d/connect uri)]
                                               (install-fixtures *conn* (concat [schema] fixtures))
                                               ?form)))])

(fact "Can create mode"
  (create-mode *conn* :nuke) => (tx-data (n-of (partial instance? datomic.db.Datum) 2)))

(fact "Can retrieve all permissions"
  (let [db (d/db *conn*)]
    (permissions db) => (just #{(just [integer? integer? integer?])})))

(fact "Can grant role permission to access resource via mode"
  (let [role [:authorization.role/id :role]
        resource [:authorization.resource/id :resource]]
    (grant *conn* role :delete resource) => (tx-data (n-of (partial instance? datomic.db.Datum) 2))))

(fact "Can revoke role's permission to access resource via mode"
  (let [role [:authorization.role/id :role]
        resource [:authorization.resource/id :resource]]
    (revoke *conn* role :read resource) => (tx-data (n-of (partial instance? datomic.db.Datum) 2))))

(fact "Can determine if role is permitted to access resource via mode"
  (let [db (d/db *conn*)
        resource [:authorization.resource/id :resource]]
    (permitted? db [:authorization.role/id :role] :read resource) => truthy
    (permitted? db [:authorization.role/id :role] :delete resource) => falsey
    (permitted? db [:authorization.role/id :role] :nuke resource) => falsey
    (permitted? db [:authorization.role/id :role1] :read resource)) => falsey)
