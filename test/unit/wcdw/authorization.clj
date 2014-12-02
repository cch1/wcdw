(ns unit.wcdw.authorization
  "Authorization unit tests"
  (:require [wcdw.authorization :refer :all]
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

(defchecker refers-to-tx-exception
  [expected]
  (checker [actual]
           (try (deref actual) false (catch Throwable e (extended-= (ex-data (.getCause e)) expected)))))

(def uri "datomic:mem://0")

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

(def role-fixtures
  [{:db/id #db/id[:db.part/roles -1000]
    :authorization.role/id :root}
   {:db/id #db/id[:db.part/roles -1]
    :authorization.role/id :role0
    :authorization.role/_children #db/id[:db.part/roles -1000]}
   {:db/id #db/id[:db.part/roles -2]
    :authorization.role/id :role1
    :authorization.role/_children #db/id[:db.part/roles -1000]}
   {:db/id #db/id[:db.part/roles -3]
    :authorization.role/id :role00
    :authorization.role/_children #db/id[:db.part/roles -1]
    :db/doc "Child of u0"}
   {:db/id #db/id[:db.part/roles -4]
    :authorization.role/id :role000
    :authorization.role/_children #db/id[:db.part/roles -3]
    :db/doc "Grandchild of u0, child of u00"}
   {:db/id #db/id[:db.part/roles -5]
    :authorization.role/id :role00x
    :authorization.role/_children #db/id[:db.part/roles -3]
    :db/doc "Child of u00 and u0"}
   ;; Wire up second parent of :role00x -not possible with map representation above due to duplicate keys
   [:db/add #db/id[:db.part/roles -1] :authorization.role/children #db/id[:db.part/roles -5]]])

(def resource-fixtures
  [{:db/id #db/id[:db.part/resources]
    :authorization.resource/id :resource0}
   {:db/id #db/id[:db.part/resources]
    :authorization.resource/id :resource1}])

(def mode-fixtures
  [{:db/id #db/id[:db.part/permissions]
    :db/ident :read}
   {:db/id #db/id[:db.part/permissions]
    :db/ident :delete}])

(def permission-fixtures
  [{:db/id #db/id[:db.part/permissions]
    :authorization.permission/role [:authorization.role/id :role00]
    :authorization.permission/resource [:authorization.resource/id :resource0]
    :authorization.permission/mode :read}])

(def fixtures [role-schema resource-schema role-fixtures resource-fixtures mode-fixtures permission-fixtures])

(defn install-fixtures [conn txs]
  (doseq [tx txs]
    (d/transact conn tx)))

(namespace-state-changes [(around :facts (do (d/delete-database uri)
                                             (d/create-database uri)
                                             ?form
                                             (d/delete-database uri)))])

(fact "Can initialize"
  (let [conn (d/connect uri)]
    (:tx-data @(initialize! conn))) => truthy)

;; The following tests interact with the database and expect fixture data to be installed afresh before each test

(namespace-state-changes [(around :facts (do (d/delete-database uri)
                                             (d/create-database uri)
                                             (let [conn (d/connect uri)]
                                               (initialize! conn)
                                               (install-fixtures conn fixtures))
                                             ?form))])

(fact "Can determine if role is authorized to access resource via mode"
  (let [db (-> uri d/connect d/db)]
    (authorized? db (d/entity db [:authorization.role/id :role0])
                 (d/entity db :read) (d/entity db [:authorization.resource/id :resource0])) => truthy
    (authorized? db (d/entity db [:authorization.role/id :role000])
                 (d/entity db :read) (d/entity db [:authorization.resource/id :resource0])) => falsey
    (authorized? db (d/entity db [:authorization.role/id :role0])
                 (d/entity db :delete) (d/entity db [:authorization.resource/id :resource0])) => falsey
    (authorized? db (d/entity db [:authorization.role/id :role0])
                 (d/entity db :read) (d/entity db [:authorization.resource/id :resource1])) => falsey))
