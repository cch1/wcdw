(ns wcdw.authorization
  "A simple Role-Based Access Control System"
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [wcdw.authorization.role :as role]
            [wcdw.authorization.permission :as permission]
            [ring.util.response :as response]))

(def ^:dynamic *role*)

(def schema
  "Embellished transaction data representing the schema of wcdw.  Compatible with rkneufeld/conformity"
  {::authorization {:requires [::roles ::permissions]
                    :txes []}
   ::roles {:txes [role/schema]}
   ::permissions {:txes [permission/schema]}})

(defmacro with-role [role & body]
  `(binding [*role* ~role] (do ~@body)))

;; Rules
(def rules (let [top '[[(authorized? ?role ?mode ?resource ?descendant)
                        (descendant+ ?role ?descendant)
                        (permitted? ?descendant ?resource ?mode _)]]]
             (concat role/rules permission/rules top)))
;; API
(defn authorized?
  "Predicate on the role-like entity having permission to access the resource-like entity via the mode"
  [db role mode resource]
  (let [authorized-role (d/q '{:find [?authorized-role .]
                               :in [$ % ?role ?mode ?resource]
                               :where [(authorized? ?role ?mode ?resource ?authorized-role)]}
                             db rules (:db/id role) (:db/id mode) (:db/id resource))]
    (if authorized-role
      (log/debugf "Authorization ✓: %s -> %s [%s] via %s" role resource mode authorized-role)
      (log/debugf "Authorization X: %s -> %s [%s]" role resource mode))
    authorized-role))

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
