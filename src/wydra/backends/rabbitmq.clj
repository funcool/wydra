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

(ns wydra.backends.rabbitmq
  "A messaging library for Clojure"
  (:require [clojure.walk :refer [stringify-keys keywordize-keys]]
            [clojure.core.async :as a]
            [zaek.core :as zk]
            [wydra.executor :as exec]
            [wydra.serializers :as serz]
            [wydra.session :as sess]
            [wydra.connection :as conn]
            [wydra.message :as msg]
            [wydra.util :as util])
  (:import java.net.URI))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Types
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare subscribe)
(declare publish)
(declare consume)
(declare produce)

(defrecord Connection [connection channel serializer subscriptions]
  java.lang.AutoCloseable
  (close [_]
    (.close ^com.rabbitmq.client.Channel channel)
    (.close ^com.rabbitmq.client.Connection connection))

  sess/ITopicSession
  (-subscribe [this topic options]
    (subscribe this topic options))

  (-publish [this topic message]
    (publish this topic message))

  sess/IQueueSession
  (-consume [this queue options]
    (consume this queue options))

  (-produce [this queue message]
    (produce this queue message)))

(alter-meta! #'->Connection assoc :private true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parse-params
  [^URI uri]
  (-> (.getQuery uri)
      (util/querystring->map)
      (keywordize-keys)))

(defn- headers->props
  [{:keys [persistent] :as headers}]
  (let [options (select-keys headers [:mode :userid :appid :reply-to
                                      :priority :type :content-type])]
    (if-not (nil? persistent)
      (if persistent
        (assoc options :mode 2)
        (assoc options :mode 1))
      options)))

(defmethod conn/-connect :rabbitmq
  [^URI uri {:keys [serializer] :or {serializer serz/*default*} :as options}]
  (let [host (.getHost uri)
        port (.getPort uri)
        options (merge (parse-params uri)
                       options
                       (when host {:host host})
                       (when port {:port port}))
        connection (zk/connect options)
        channel (zk/channel connection options)]
    (Connection. connection channel serializer (atom {}))))

(defn- subscribe
  [conn topic options]
  (let [channel (:channel conn)
        defaults {:exclusive true :autodelete true}
        ch (or (:chan options) (a/chan))
        queue (zk/declare-queue channel "" (merge defaults options))]
    (zk/bind-queue channel queue "amq.topic" topic)
    (let [lock (a/chan)
          stag (zk/consume channel queue
                           (fn [tag env props data]
                             (let [serializer (:serializer conn)
                                   data (serz/decode serializer data)
                                   message (-> (msg/-message data props)
                                               (assoc :wydra/ack #(zk/ack channel tag)))]
                               ;; Blocking call is performed because the rabbitmq
                               ;; has blocking api.
                               (let [res (a/>!! ch message)]
                                 (when-not (true? res)
                                   (a/close! lock))))))]
      (a/take! lock #(zk/cancel channel stag))
      ch)))

(defn- publish
  [conn topic message]
  (let [serializer (:serializer conn)
        channel (:channel conn)
        message-body (msg/-body message)
        message-opts (msg/-headers message)
        content-type (serz/get-content-type serializer)
        message (serz/encode serializer message-body)
        props (headers->props (assoc message-opts :content-type content-type))
        ch (a/chan)]
    (exec/execute #(try
                     (zk/publish channel "amq.topic" topic message props)
                     (catch Throwable e
                       ;; TODO: properly handle errors
                       (.printStackTrace e))
                     (finally
                       (a/close! ch))))
    ch))

(def ^:const ^:private +consume-defaults+
  {:durable false
   :messagettl 600
   :exclusive false
   :autodelete true})

(defn- consume
  [conn queue options]
  (let [channel (:channel conn)
        ch (or (:chan options) (a/chan))
        lock (a/chan)
        options (merge +consume-defaults+ options)
        consumer (fn [tag env props data]
                   (let [serializer (:serializer conn)
                         data (serz/decode serializer data)
                         message (-> (msg/-message data props)
                                     (assoc :wydra/ack #(zk/ack channel tag)))]
                     ;; Blocking call is performed because the rabbitmq has
                     ;; blocking api.
                     (let [res (a/>!! ch message)]
                       (when-not (true? res)
                         (a/close! lock)))))]


    (zk/declare-queue channel queue options)
    (let [stag (zk/consume channel queue consumer options)]
      (a/take! lock #(zk/cancel channel stag))
      ch)))

(defn- produce
  [conn queue message]
  (let [serializer (:serializer conn)
        channel (:channel conn)
        message-body (msg/-body message)
        message-opts (msg/-headers message)
        content-type (serz/get-content-type serializer)
        message (serz/encode serializer message-body)
        props (headers->props (assoc message-opts :content-type content-type))
        ch (a/chan)]
    (exec/execute #(try
                     (zk/publish channel "" queue message props)
                     (catch Throwable e
                       ;; TODO: properly handle errors
                       (.printStackTrace e))
                     (finally
                       (a/close! ch))))
    ch))
