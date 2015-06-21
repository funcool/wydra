(ns wydra.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as a]
            [wydra.core :as wy]))

(deftest connection-creation
  (let [p1 (promise)
        p2 (promise)
        p3 (promise)
        conn1 (wy/connect "rabbitmq://localhost/")
        conn2 (wy/connect "rabbitmq://localhost/")
        s1 (wy/subscribe conn1 "foo.bar")]
    (a/go
      (let [msg (a/<! s1)]
        (deliver p1 (:body msg))
        (wy/ack msg))
      (let [msg (a/<! s1)]
        (deliver p2 (:body msg))
        (wy/ack msg))
      (loop []
        (when-let [msg (a/<! s1)]
          (println msg)
          (wy/ack msg)
          (recur)))
      (deliver p3 true))
    (let [msg1 (wy/message {:foo "hola"} {:mode 2})
          msg2 (wy/message {:foo "hello"} {:mode 2})]
      (a/<!! (wy/publish conn2 "foo.bar" msg1))
      (a/<!! (wy/publish conn2 "foo.bar" msg2)))

    (is (= {:foo "hola"} (deref p1 1000 nil)))
    (is (= {:foo "hello"} (deref p2 1000 nil)))
    (a/close! s1)
    (is (deref p3 1000 false))))
