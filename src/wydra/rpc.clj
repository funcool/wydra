;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
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

(ns wydra.rpc
  (:require [clojure.core.async :as a]
            [clj-uuid :as uuid]
            [wydra.message :as msg]
            [wydra.core :as wyd])
  (:import java.util.concurrent.TimeUnit
           java.util.concurrent.Executors))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Client
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:no-doc
  +scheduler+ (Executors/newScheduledThreadPool 1))

(defn timeout
  [ms]
  (let [ch (a/chan)]
    (.schedule +scheduler+ #(a/close! ch) ms TimeUnit/MILLISECONDS)
    ch))

(defprotocol IClient
  (-ask [_ payload options]))

(deftype Client [conn id queue state]
  java.lang.AutoCloseable
  (close [_]
    (.close conn))

  IClient
  (-ask [_ payload options]
    (let [msgid (uuid/v4)
          body {:payload payload
                :type :rpc/request
                :id msgid
                :reply-to (str id)}
          msg (wyd/message body)
          chs (a/chan 1)]
      (.put state msgid chs)
      (a/go
        (a/<! (wyd/produce conn queue msg))
        (let [cht (timeout (:timeout options 6000))
              [val port] (a/alts! [chs cht])]
          (a/close! chs)
          (a/close! cht)
          (.remove state msgid)
          (if (identical? port cht)
            (vector :rpc/timeout nil)
            (let [response (get-in val [:body :payload])]
              (vector :rpc/response response))))))))

(defn client-rcv-loop
  [conn id state]
  (let [source (wyd/consume conn (str id) {:autoack true})]
    (a/go-loop []
      (when-let [{:keys [body] :as msg} (a/<! source)]
        (let [msgid (:id body)
              ch (.remove state msgid)]
          (when-not (nil? ch)
            (a/offer! ch msg))
          (recur))))))


(defn client
  "Create a new server instance."
  ([conn] (client conn nil))
  ([conn {:keys [queue] :as options}]
   (when-not queue
     (throw (ex-info "No queue option is provided." {})))
   (let [pub-ch (a/chan 256)
         pub (a/pub pub-ch (comp :id :body))
         state (java.util.concurrent.ConcurrentHashMap.)
         id (uuid/v4)]
     (client-rcv-loop conn id state)
     (Client. conn id queue state))))

(defn ask
  "Perform the ask operation."
  ([client message]
   (-ask client message {}))
  ([client message options]
   (-ask client message options)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IHandlerResponse
  (-handle-response [_ conn msg]))

(defprotocol IFormatErrorResponse
  (-format-error-response [_]))

(extend-protocol IFormatErrorResponse
  clojure.lang.ExceptionInfo
  (-format-error-response [error]
    (let [message (.getMessage error)
          data (ex-data error)]
      {:type :rpc/error
       :message message
       :data data}))

  java.lang.Exception
  (-format-error-response [err]
    (let [message (.getMessage err)]
      {:type :rpc/error
       :message message
       :data nil})))

(extend-protocol IHandlerResponse
  clojure.lang.IPersistentMap
  (-handle-response [response conn msg]
    (let [{:keys [id reply-to]} (msg/-body msg)
          body {:type :rpc/response :id id :payload response}
          message (wyd/message body)]
      (wyd/produce conn reply-to message)))

  java.lang.Exception
  (-handle-response [err conn msg]
    (-> (-format-error-response err)
        (-handle-response conn msg))))

(defn- handle-message
  [conn handler msg]
  (let [{:keys [payload]} (msg/-body msg)]
    (try
      (let [response (handler payload)]
        (-handle-response response conn msg))
      (catch Exception e
        (-handle-response e conn msg)))))

(deftype Server [conn options ch]
  java.lang.AutoCloseable
  (close [_] (a/close! ch)))

(defn server
  "Create a new server instance."
  ([conn] (server conn {}))
  ([conn {:keys [queue handler] :as options}]
   (when-not queue
     (throw (ex-info "No queue option is provided." {})))
   (when-not handler
     (throw (ex-info "No handler option is provided." {})))
   (let [ss (wyd/consume conn queue)]
     (a/go-loop []
       (when-let [msg (a/<! ss)]
         (a/<! (handle-message conn handler msg))
         (wyd/ack msg)
         (recur)))
     (Server. conn options ss))))
