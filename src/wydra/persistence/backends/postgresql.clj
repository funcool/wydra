;; Copyright (c) 2015 Andrey Antukh <niwi@niwi.nz>
;; All rights reserved.
;;
;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions are met:
;;
;; * Redistributions of source code must retain the above copyright notice, this
;;   list of conditions and the following disclaimer.
;;
;; * Redistributions in binary form must reproduce the above copyright notice,
;;   this list of conditions and the following disclaimer in the documentation
;;   and/or other materials provided with the distribution.
;;
;; THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
;; AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
;; IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
;; DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
;; FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
;; DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
;; SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
;; CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
;; OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
;; OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(ns wydra.persistence.backends.postgresql
  (:require [clojure.walk :refer [stringify-keys keywordize-keys]]
            [clojure.core.async :as a]
            [futura.executor :as exec]
            [suricatta.core :as sc]
            [suricatta.dsl :as dsl]
            [hikari-cp.core :refer hikari]
            [wydra.persistence.database :as db]
            [wydra.persistence.protocols :as proto]
            [wydra.util :as util])
  (:import java.net.URI
           java.util.concurrent.ForkJoinPool
           java.util.concurrent.Executor))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Types
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare bootstrap)

(defrecord Transactor [context opsqueue]
  proto/ITransactorInternal
  (bootstrap [this]
    (bootstrap this)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parse-params
  [^URI uri]
  (let [userinfo (.getUserInfo uri)
        host (.getHost uri)
        port (.getPort uri)]
    (merge
     (-> (.getQuery uri)
         (util/querystring->map)
         (keywordize-keys))
     {:server-name host}
     (when-not (empty? userinfo)
       (let [[user password] (str/split userinfo ":")]
         {:username user
          :password password}))
     (when port
       {:port-number port}))))

(def ^{:private true
       :static true}
  connection-defaults
  {:connection-timeout 30000
   :idle-timeout 600000
   :max-lifetime 1800000
   :minimum-idle 10
   :maximum-pool-size  10
   :adapter "postgresql"
   :server-name "localhost"
   :port-number 5432})

(defmethod db/open :postgresql
  [^URI uri options]
  (let [options (merge options (parse-params uri))
        datasource (hikari/make-datasource
                    (merge connection-defaults options))]
    (Transactor. (sc/context datasource)
                 (a/chan (:ops-queue-size options 256)))))

(defn- bootstrap
  [conn]
  (let [ctx (:context conn)
        sql (slurp (io/resource "persistence/postgresql/bootstrap.sql"))]
    (sc/atomic ctx
      (sc/execute ctx sql))))



