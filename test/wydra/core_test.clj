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
  (let [conn1 (wym/connect "rabbitmq://localhost/" )
        conn2 (wym/connect "rabbitmq://localhost/" {:prefetch 4})
        client (rpc/client conn1 {:queue "tasks"})
        server (rpc/server conn2 {:queue "tasks"
                                  :handler (fn [request]
                                             {:response (:require request)})})]
    (let [request {:require 1}
          [type response] (a/<!! (rpc/ask client request))]
      (is (= type :rpc/response))
      (is (= response {:response 1})))))

;; (deftest rpc-benchmark
;;   (let [conn1 (wym/connect "rabbitmq://localhost/")
;;         conn2 (wym/connect "rabbitmq://localhost/" {:prefetch 10})
;;         conn3 (wym/connect "rabbitmq://localhost/" {:prefetch 10})
;;         client (rpc/client conn1 {:queue "tasks"})
;;         server1 (rpc/server conn2)
;;         server2 (rpc/server conn3)]

;;     (rpc/listen! server1 "tasks"
;;                  (fn [request] {:response (:require request)}))

;;     (rpc/listen! server2 "tasks"
;;                  (fn [request] {:response (:require request)}))

;;     (let [timeouts (volatile! 0)
;;           min-time (volatile! 999999999)
;;           max-time (volatile! 0)
;;           total-time (volatile! 0)
;;           tasks-a (agent nil)
;;           num-ops 100000]
;;       (dotimes [i num-ops]
;;         ;; (Thread/sleep 10)
;;         (let [request {:require i}
;;               start-t (System/nanoTime)
;;               [type response] (a/<!! (rpc/ask client request))]
;;           (case type
;;             :rpc/response
;;             (let [end-t (System/nanoTime)
;;                   res-t (- end-t start-t)]
;;               (send tasks-a (fn [_]
;;                               (vswap! total-time + res-t)
;;                               (when (< res-t @min-time)
;;                                 (vreset! min-time res-t))
;;                               (when (> res-t @max-time)
;;                                 (println "increasing to" res-t)
;;                                 (vreset! max-time res-t)))))

;;             :rpc/timeout
;;             (send tasks-a (fn [_]
;;                             (vswap! timeouts inc))))))

;;       (shutdown-agents)
;;       (Thread/sleep 1000)
;;       (println "Timeouts:" @timeouts)
;;       (println "Latency min:" (/ @min-time 1e6) "ms")
;;       (println "Latency max:" (/ @max-time 1e6) "ms")
;;       (println "Latency avg:" (/ (/ @total-time 1e6) num-ops) "ms"))))


