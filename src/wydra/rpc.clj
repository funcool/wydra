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
            [wydra.core :as wyd]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Client
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IClient
  (-ask [_ payload options]))

(deftype Client [conn id pub queue]
  java.lang.AutoCloseable
  (close [_]
    (.close conn))

  IClient
  (-ask [_ payload options]
    (let [{:keys [timeout] :or {timeout 6000}} options
          msgid (uuid/v1)
          body {:payload payload
                :type :rpc/request
                :id msgid
                :reply-to (str id)}
          msg (wyd/message body)
          chs (a/chan (a/dropping-buffer 1))
          cht (a/timeout timeout)]
      (a/sub pub msgid chs true)
      (a/go
        (a/<! (wyd/produce conn queue msg))
        (let [[val port] (a/alts! [chs cht])]
          (a/close! chs)
          (a/close! cht)
          (if (identical? port cht)
            (vector :rpc/timeout nil)
            (let [response (get-in val [:body :payload])]
              (wyd/ack val)
              (vector :rpc/response response))))))))

(defn client
  "Create a new server instance."
  ([conn] (client conn nil))
  ([conn {:keys [queue] :as options}]
   (when-not queue
     (throw (ex-info "No queue option is provided." {})))
   (let [pub-ch (a/chan 256)
         pub (a/pub pub-ch (comp :id :body))
         id (uuid/v4)
         source (wyd/consume conn (str id))]
     (a/pipe source pub-ch true)
     (Client. conn id pub queue))))

(defn ask
  "Perform the ask operation."
  ([client message]
   (-ask client message {}))
  ([client message options]
   (-ask client message options)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IServer
  (-listen [_ queue handler]))

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
      (a/go
        (a/<! (wyd/produce conn reply-to message))
        (wyd/ack msg))))

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

(deftype Server [conn options]
  java.lang.AutoCloseable
  (close [_]
    (.close conn))

  IServer
  (-listen [_ queue handler]
    (let [ss (wyd/consume conn queue)
          ch (a/chan (a/sliding-buffer 6))]
      (a/go-loop []
        (if-let [msg (a/<! ss)]
          (do
            (a/<! (handle-message conn handler msg))
            (recur))
          (a/close! ch)))
      ch)))

(defn server
  "Create a new server instance."
  ([conn] (server conn {}))
  ([conn options]
   (Server. conn options)))

(defn listen!
  [srv queue handler]
  (-listen srv queue handler))

