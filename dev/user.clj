(ns user
  (:require [datomic.api :refer [q db] :as d]))

(def uri "datomic:mem://wcdw")
(def conn (atom nil))

(defn init []
  (d/delete-database uri)
  (d/create-database uri)
  (reset! conn (d/connect uri)))
