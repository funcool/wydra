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

(ns wydra.messaging.serializers
  "A serializers abstraction for messaging."
  (:require [clojure.edn :as edn]
            [cheshire.core :as json]
            [cognitect.transit :as transit]
            [wydra.util :as util])
  (:import java.io.ByteArrayOutputStream
           java.io.ByteArrayInputStream))

(defprotocol ISerializer
  (^:private encode* [_ data] "Encode data.")
  (^:private decode* [_ data] "Decode data.")
  (^:private content-type* [_] "Obtain contentype."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Serializers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn edn
  "Edn serializer constructor."
  []
  (reify ISerializer
    (content-type* [_]
      "application/edn")
    (encode* [_ data]
      (-> (pr-str data)
          (util/str->bytes)))
    (decode* [_ data]
      (-> (util/bytes->str data)
          (edn/read-string)))))

(defn json
  "JSON serializer constructor."
  []
  (reify ISerializer
    (content-type* [_]
      "application/json")
    (encode* [_ data]
      (-> (json/encode data {:escape-non-ascii true})
          (util/str->bytes)))
    (decode* [_ data]
      (-> (util/bytes->str data)
          (json/decode true)))))

(defn transit
  "Transint serializer constructor.

  With optionally type parameter that allows specify the
  underlying serialization format for transit. The type
  should be one of the following keywords: `:json`,
  `:json-verbose` and `:msgpack`.

  The default format is msgpack."
  ([] (transit :msgpack))
  ([type]
   (reify ISerializer
    (content-type* [_]
      (str "application/transit+" (name type)))
     (encode* [_ data]
       (with-open [out (ByteArrayOutputStream.)]
         (let [w (transit/writer out type)]
           (transit/write w data)
           (.toByteArray ^ByteArrayOutputStream out))))
     (decode* [_ data]
       (with-open [in (ByteArrayInputStream. data)]
         (let [r (transit/reader in type)]
           (transit/read r)))))))

(def ^:dynamic
  *default* (transit :msgpack))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn encode
  "Encode message using specified serializer instance."
  [serializer data]
  (encode* serializer data))

(defn decode
  "Decode message using specified serializer instance."
  [serializer ^bytes data]
  (decode* serializer data))

(defn get-content-type
  "Get content type for the serializer."
  [serializer]
  (content-type* serializer))

;; (defn resolve-by-content-type
;;   [^String content-type & args]
;;   (case content-type
;;     "application/json" (apply json args)
;;     "application/edn" (apply edn args)
;;     "application/transit+msgpack" (apply transit :msgpack args)))
