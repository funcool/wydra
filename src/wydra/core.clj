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

(ns wydra.core
  "A messaging library for Clojure"
  (:require [clojure.core.async :as a]
            [wydra.impl.connection :as conn]
            [wydra.impl.message :as msg]
            [wydra.impl.backends.rabbitmq]))

(defn connect
  "Given a uri and optionally a options hash-map,
  create a connection to the message broker.

  The backend used for the connection is resolved
  using the uri scheme and backend options are parsed
  from the query params.

  This function accepts additionally a options map
  thar serves for configure serializer, compression
  and other similar things that are not related
  to the connection parameters."
  ([uri]
   (conn/connect (conn/->uri uri) {}))
  ([uri options]
   (conn/connect (conn/->uri uri) options)))

(defn message
  "A message instace constructor."
  ([body]
   (msg/message body {}))
  ([body options]
   (msg/message body options)))

(defn subscribe
  "Create a subscription to a specific topic
  and return a core.async channel that will
  receive the incoming messages.

  Optionally you can pass your own channel
  for make easy use of transducers."
  ([conn topic]
   (subscribe conn topic (a/chan)))
  ([conn topic ch]
   (conn/subscribe conn topic ch)))

(defn publish
  "Publish asynchronously a message into
  a specific topic. It returns a core.async
  channel that will be closed when the operation
  is completed."
  [conn topic message]
  (let [message (msg/message message nil)]
    (conn/publish conn topic message)))

(defn ack
  "Function that makes a message recevied.

  Is a mandatory operation for tell the system
  that the received message is succesfully
  received and allow receive the next message
  (the messages are received in one by one)."
  [message]
  {:pre [(contains? message :wydra/ack)]}
  (let [ackfn (:wydra/ack message)]
    (ackfn)))
