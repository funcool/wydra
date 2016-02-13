(ns wydra.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [wydra.core :as wym]
            [wydra.rpc :as rpc]))

(deftest basic-test
  (let [p1 (promise)
        p2 (promise)
        p3 (promise)
        conn1 (wym/connect "rabbitmq://localhost/")
        conn2 (wym/connect "rabbitmq://localhost/")
        s1 (wym/subscribe conn1 "foo.bar")]
    (a/go
      (let [msg (a/<! s1)]
        (deliver p1 (:body msg))
        (wym/ack msg))
      (let [msg (a/<! s1)]
        (deliver p2 (:body msg))
        (wym/ack msg))
      (loop []
        (when-let [msg (a/<! s1)]
          (println msg)
          (wym/ack msg)
          (recur)))
      (deliver p3 true))

    (let [msg1 (wym/message {:foo "hola"} {:mode 2})
          msg2 (wym/message {:foo "hello"} {:mode 2})]
      (a/<!! (wym/publish conn2 "foo.bar" msg1))
      (a/<!! (wym/publish conn2 "foo.bar" msg2)))

    (is (= {:foo "hola"} (deref p1 1000 nil)))
    (is (= {:foo "hello"} (deref p2 1000 nil)))
    (a/close! s1)
    (is (deref p3 1000 false))))


(deftest rpc-test
  (let [conn1 (wym/connect "rabbitmq://localhost/")
        conn2 (wym/connect "rabbitmq://localhost/")
        client (rpc/client conn1 {:queue "tasks"})
        server (rpc/server conn2)]
    (println client)
    (println server)
    (rpc/listen! server "tasks"
                 (fn [request] {:foo "foo"}))

    (let [response (a/<!! (rpc/ask client {:baz "baz"}))]
      (println response))))


