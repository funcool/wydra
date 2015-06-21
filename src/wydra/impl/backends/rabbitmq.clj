2;; Copyright (c) 2015 Andrey Antukh <niwi@niwi.nz>
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

(ns wydra.impl.rabbitmq
  "A messaging library for Clojure"
  (:require [clojure.walk :refer [stringify-keys keywordize-keys]]
            [clojure.core.async :as a]
            [zaek.core :as zk]
            [wydra.impl.serializers :as serializers]
            [wydra.impl.connection :as conn]
            [wydra.impl.message :as msg]
            [wydra.util :as util])
  (:import java.net.URI
           java.util.concurrent.ForkJoinPool
           java.util.concurrent.Executor))

(def ^:dynamic *executor* (ForkJoinPool/commonPool))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Types
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare subscribe)
(declare publish)
;; (declare unsubscribe)

(defrecord Connection [connection channel serializer subscriptions]
  java.lang.AutoCloseable
  (close [_]
    (.close ^com.rabbitmq.client.Channel channel)
    (.close ^com.rabbitmq.client.Connection connection))

  conn/ISession
  (subscribe [this topic ch]
    (subscribe this topic ch))

  ;; (unsubscribe [this topic callback]
  ;;   (unsubscribe this topic callback))

  (publish [this topic message]
    (publish this topic message)))

(alter-meta! #'->Connection assoc :private true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parse-params
  [^URI uri]
  (-> (.getQuery uri)
      (util/querystring->map)
      (keywordize-keys)))

(defmethod conn/connect :rabbitmq
  [^URI uri {:keys [serializer] :or {serializer serializers/*default*}}]
  (let [params (parse-params uri)
        host (.getHost uri)
        port (.getPort uri)
        connection (zk/connect (merge params
                                      (when host {:host host})
                                      (when port {:port port})))
        channel (zk/channel connection)]
    (Connection. connection channel serializer (atom {}))))

(defn- subscribe
  [conn topic ch]
  (let [channel (:channel conn)
        queue (zk/declare-queue! channel "" {:exclusive true :persistent true})]
    (zk/bind-queue! channel queue "amq.topic" topic)
    (let [lock (a/chan)
          stag (zk/consume channel queue
                           (fn [tag env props data]
                             (let [serializer (:serializer conn)
                                   data (serializers/decode serializer data)
                                   message (-> (msg/message data props)
                                               (assoc :wydra/ack #(zk/ack channel tag)))]
                               ;; Blocking call is performed because the rabbitmq has
                               ;; blocking api.
                               (let [res (a/>!! ch message)]
                                 (when-not (true? res)
                                   (a/close! lock))))))]
      (a/take! lock (fn [_]
                      (zk/cancel channel stag)))
      ch)))

;; (defn- unsubscribe
;;   [conn topic callback]
;;   (let [channel (:channel conn)
;;         tag (get @(:subscriptions conn) topic)]
;;     (if tag
;;       (do
;;         (zk/cancel channel tag)
;;         (swap! (:subscriptions conn) dissoc topic)
;;         (callback :ok nil))
;;       (callback :error (IllegalArgumentException. "no subscription.")))))

(defn- headers->props
  [{:keys [persistent] :as headers}]
  (let [options (select-keys headers [:mode :userid :appid :reply-to :priority :type :content-type])
        props (transient options)]
    (when-not (nil? persistent)
      (if persistent
        (assoc! props :mode 2)
        (assoc! props :mode 1)))
    (persistent! props)))

(defn- publish
  [conn topic message]
  (let [ch (a/chan)
        serializer (:serializer conn)
        channel (:channel conn)
        message-body (msg/get-body message)
        message-opts (msg/get-options message)
        content-type (serializers/get-content-type serializer)
        message (serializers/encode serializer message-body)
        props (headers->props (assoc message-opts :content-type content-type))]
    (.execute ^Executor *executor* (reify Runnable
                                     (run [_]
                                       (try
                                         (zk/publish channel "amq.topic" topic message props)
                                         (catch Throwable e
                                           ;; TODO: properly handle errors
                                           (.printStackTrace e))
                                         (finally
                                           (a/close! ch))))))
    ch))
