(defproject funcool/wydra "0.1.0-SNAPSHOT"
  :description "Messaging library for clojure."
  :url "https://github.com/funcool/wydra"
  :license {:name "BSD (2-Clause)"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :javac-options ["-target" "1.8" "-source" "1.8" "-Xlint:-options"]
  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/core.async "0.2.374"]
                 [funcool/zaek "0.1.0-SNAPSHOT"]
                 [danlentz/clj-uuid "0.1.6"]
                 [funcool/cuerdas "0.7.1"]
                 [funcool/promesa "0.8.1"]
                 [com.cognitect/transit-clj "0.8.285"]
                 [cheshire "5.5.0"]]
  :profiles {:dev {:global-vars {*warn-on-reflection* true}}}
  :plugins [[lein-ancient "0.6.7"]])


