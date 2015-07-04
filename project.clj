(defproject funcool/wydra "0.1.0-SNAPSHOT"
  :description "Messaging library for clojure."
  :url "https://github.com/funcool/wydra"
  :license {:name "BSD (2-Clause)"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :javac-options ["-target" "1.8" "-source" "1.8" "-Xlint:-options"]
  :dependencies [[org.clojure/clojure "1.7.0" :scope "provided"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [funcool/zaek "0.1.0-SNAPSHOT"]
                 [funcool/cuerdas "0.5.0"]
                 [funcool/futura "0.3.0"]
                 [funcool/suricatta "0.3.1"]
                 [hikari-cp "1.2.4"]
                 [com.cognitect/transit-clj "0.8.275"]
                 [cheshire "5.5.0"]]
  :profiles {:dev {:global-vars {*warn-on-reflection* true}}})

