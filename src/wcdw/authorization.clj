(ns roomkey.authorization
  "A simple Role-Based Access Control System"
  (:require [clojure.tools.logging :as log]
            [clojure.set :as set]
            [datomic.api :as d]
            [roomkey.authorization.database :as rkdb]
            [roomkey.authorization.role :as role]
            [roomkey.authorization.permission :as permission]
            [ring.util.response :as response]))

(def ^:dynamic *role*)

(def conn (let [uri "datomic:mem://0"]
            (d/delete-database uri)
            (d/create-database uri)
            (d/connect uri)))

(def ^:private root :root)

(rkdb/install-schema conn)
(defn install-root-role
  [conn root]
  (d/transact conn [{:db/id #db/id[:db.part/roles]
                     :authorization.role/id root}]))

(install-root-role conn root)

(defmacro with-role [role & body]
  `(binding [*role* ~role] (do ~@body)))

;;;;;;;;;;;;; Roles

(defn roles [db]
  (map (fn [[id]] (d/entity db id))
       (d/q '[:find ?e :where [?e :authorization.role/id ?v]] db)))

(defn role-graph
  [db]
  (loop []))

(defn create
  [conn ident parent-ident]
  {:pre [(keyword? ident) (keyword? parent-ident) (not= :root ident)]}
  (let [db (d/db conn)
        parent (first (d/q '[:find ?e :in $ ?ident :where [?e :authorization.role/id ?ident]] db parent-ident))]
    (assert parent "Parent could not be found")
    (d/transact conn [{:db/id #db/id[:db.part/roles]
                       :authorization.role/id ident
                       :authorization.role/parent parent}])))

(defn assign
  "(Re)assign the child role to the parent role"
  [conn childk parentk]
  (let [db (d/db conn)
        parent-dbid (first (d/q '[:find ?e :where [?e :authorization.role/id _]] db parentk))]
    (d/transact conn [{:db/id #db/id[:db.part/roles]
                       :authorization.role/id childk
                       :authorization.role/parent parent-dbid}])))

(defn unassign
  "Remove the child role from the parent role"
  [parent child & children]
  (swap! roles update-in [parent] (fn [roles] (apply disj (or roles #{}) child children))))

(def ^:private descend
  ;; Return all nodes encountered walking the tree, represented as a map of node->uubnodes, starting at k
  (fn [tree node]
    (let [children (set (mapcat #(descend tree %) (get tree node #{})))]
      (conj children node))))

;; Permissions
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

;; API
(defn authorized?
  "Return boolean predicated on the given identity having permission to access the given resource in the given mode"
  ([mode resource] (authorized? *role* mode resource))
  ([role mode resource]
     (let [permitted-roles (get-in @permissions [resource mode] #{})
           role-family (descend @roles role)
           authorized-role (first (set/intersection permitted-roles role-family))]
       (if authorized-role
         (log/debugf "Authorization ✓: %s -> %s [%s] via %s" role resource mode authorized-role)
         (log/debugf "Authorization X: %s -> %s [%s]" role resource mode))
       authorized-role)))

(defn assert-authorized
  [& args]
  (when-not (apply authorized? args)
    (throw (ex-info "Not authorized" {}))))

(defn restrict
  "Restricts access to the function f to entities authorized to access the given resource in the given mode"
  [f mode resource]
  (fn [& args]
    (assert-authorized mode resource)
    (apply f args)))

(defmacro when-authorized [mode resource & body]
  `(do (assert-authorized ~mode ~resource)
       ~@body))

(defn wrap-request-handler
  "Wrap handler with an exception handler that will return an appropriate
   response when the provided handler throws an authorization exception."
  [handler & {:keys [message identification] :or {message "No Authorization"}}]
  (let [denied  (-> (response/response message)
                    (response/status ,, 403)
                    (response/content-type ,, "text/plain"))]
    (fn [request]
      (let [role (identification request)]
        (try (with-role role
               (handler request))
             (catch clojure.lang.ExceptionInfo e
               denied))))))
