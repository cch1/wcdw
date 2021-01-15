(ns unit.wcdw.authorization
  "Authorization unit tests"
  (:require [wcdw.authorization :refer :all]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [io.rkn.conformity :as c]
            [midje.sweet :refer :all]
            [midje.checking.core :refer [extended-=]]))

(def uri "datomic:mem://0")

(def ^:dynamic *conn*)

;; NB: Datomic transaction return map-like associations whose values are *not* suitable for the eager
;; evaluation that midje applies to the LHS of facts.  Reference: https://github.com/marick/Midje/issues/269
;; These (RHS) checkers should make it easy to check the results of transactions with corrupting the LHS
;; of the midje fact production with test-specific code.
(defchecker tx-data
  [expected]
  (checker [actual]
           (extended-= (-> actual deref :tx-data vec) expected)))

(defchecker refers-to-tx-exception
  [expected]
  (checker [actual]
           (try (deref actual) false (catch Throwable e (extended-= (ex-data (.getCause e)) expected)))))

(namespace-state-changes [(around :facts (do (d/delete-database uri)
                                             (d/create-database uri)
                                             (binding [*conn* (d/connect uri)]
                                               ?form)))])

(fact "Can initialize"
      (c/ensure-conforms *conn* :confirmity/conformed-norms schema (keys schema)) => truthy)

(def norms
  {::resource-schema
   {:requires []
    :txes [[{:db/id          #db/id[:db.part/db]
             :db/ident       :authorization.resource/id
             :db/valueType   :db.type/keyword
             :db/unique      :db.unique/value
             :db/cardinality :db.cardinality/one
             :db.install/_attribute :db.part/db}
            {:db/id #db/id[:db.part/db]
             :db/ident :db.part/resources
             :db.install/_partition :db.part/db}]]}
   ::role-fixtures
   {:requires [:wcdw.authorization/roles]
    :txes [[{:db/id #db/id[:wcdw/roles -1000]
             :authorization.role/id :root}
            {:db/id #db/id[:wcdw/roles -1]
             :authorization.role/id :role0
             :authorization.role/_children #db/id[:wcdw/roles -1000]}
            {:db/id #db/id[:wcdw/roles -2]
             :authorization.role/id :role1
             :authorization.role/_children #db/id[:wcdw/roles -1000]}
            {:db/id #db/id[:wcdw/roles -3]
             :authorization.role/id :role00
             :authorization.role/_children #db/id[:wcdw/roles -1]
             :db/doc "Child of u0"}
            {:db/id #db/id[:wcdw/roles -4]
             :authorization.role/id :role000
             :authorization.role/_children #db/id[:wcdw/roles -3]
             :db/doc "Grandchild of u0, child of u00"}
            {:db/id #db/id[:wcdw/roles -5]
             :authorization.role/id :role00x
             :authorization.role/_children #db/id[:wcdw/roles -3]
             :db/doc "Child of u00 and u0"}
            ;; Wire up second parent of :role00x -not possible with map representation above due to duplicate keys
            [:db/add #db/id[:wcdw/roles -1] :authorization.role/children #db/id[:wcdw/roles -5]]]]}
   ::resource-fixtures
   {:requires [::resource-schema]
    :txes [[{:db/id #db/id[:db.part/resources]
             :authorization.resource/id :resource0}
            {:db/id #db/id[:db.part/resources]
             :authorization.resource/id :resource1}]]}
   ::mode-fixtures
   {:requires []
    :txes [[{:db/ident :read}
            {:db/ident :delete}]]}
   ::permission-fixtures
   {:requires [:wcdw.authorization/permissions ::role-fixtures ::resource-fixtures ::mode-fixtures]
    :txes [[{:db/id #db/id[:wcdw/permissions]
             :authorization.permission/role [:authorization.role/id :role00]
             :authorization.permission/resource [:authorization.resource/id :resource0]
             :authorization.permission/mode :read}]]}})

;; The following tests interact with the database and expect fixture data to be installed afresh before each test

(namespace-state-changes [(around :facts (do (d/delete-database uri)
                                             (d/create-database uri)
                                             (binding [*conn* (d/connect uri)]
                                               (c/ensure-conforms *conn* (merge norms schema))
                                               ?form)))])

(fact "Can determine if role is authorized to access resource via mode"
      (let [db (d/db *conn*)]
        (authorized? db (d/entity db [:authorization.role/id :role0])
                     (d/entity db :read) (d/entity db [:authorization.resource/id :resource0])) => truthy
        (authorized? db (d/entity db [:authorization.role/id :role000])
                     (d/entity db :read) (d/entity db [:authorization.resource/id :resource0])) => falsey
        (authorized? db (d/entity db [:authorization.role/id :role0])
                     (d/entity db :delete) (d/entity db [:authorization.resource/id :resource0])) => falsey
        (authorized? db (d/entity db [:authorization.role/id :role0])
                     (d/entity db :read) (d/entity db [:authorization.resource/id :resource1])) => falsey))
