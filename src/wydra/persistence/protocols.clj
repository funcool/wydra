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

(ns wydra.persistence.protocols
  "Event source based persistence.")

;; Documentation:
;;
;; The `bootstrap` function should be executed internally on the
;; transactor is initialized and create if need all necesary
;; tables or similar that are needed for operate successfully.

(defprotocol ItransactorInternal
  (bootstrap [_] "Execute initial operations.")

(defprotocol ITransactor
  (entity [_ eid] "Get a dynamic map that represents an entity id.")
  (schema [_ s] "Execute the schema manipulation commands.")
  (transact [_ data] "Register a transaction.")
  (query [_ spec] "Query the database.")
  (pull [_ spec entity] "Pull a entity from the database."))

;; The `schema` function should be used for execute schema manipulation
;; commands, defined as data-driven DSL.
;; The DSL consists in something similar to this:

(comment
  (wys/schema db [[:add :user/username {:unique true :type :text}]
                  [:alter :user/age {:type :short}]
                  [:rename :user/name :user/fullname]]))

;; The `transact` function should be used to persist a collection
;; of facts.

(comment
  (wys/transact db [[:db/add eid attrname attrvalue]
                    [:db/retract eid attrname attrvalue]]))


