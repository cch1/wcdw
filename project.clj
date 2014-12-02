(defproject com.roomkey/loyalty "0.1.0-SNAPSHOT"
  :description "Library for Role-Based Access Control"
  :url "https://github.com/cch1/wcdw"
  :license {:name "Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version"
            :distribution :manual
            :comments "All rights reserved"}
  :plugins []
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [com.datomic/datomic-free "0.9.5078" :exclusions [org.slf4j/slf4j-nop
                                                                   org.slf4j/log4j-over-slf4j
                                                                   joda-time
                                                                   commons-codec]]
                 [org.clojure/tools.logging "0.3.1"]
                 [ring/ring-core "1.3.2"]]
  :jvm-opts ["-server" "-Dlog4j.debug=false" "-Xms256M" "-Xmx1g"
             "-Djava.io.tmpdir=./tmp"] ;; This ensures resources/log4j.properties works as designed.
  :profiles {:dev {:resource-paths ["dev-resources"]
                   :source-paths ["util"]
                   :dependencies [[midje "1.6.3"]
                                  [org.slf4j/slf4j-api "1.7.7"]
                                  [org.slf4j/jcl-over-slf4j "1.7.7"]
                                  [org.slf4j/slf4j-log4j12 "1.7.7"]
                                  [log4j/log4j "1.2.17"]]}}
  :reload-paths ["src"])
