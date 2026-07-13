(ns transportclerk.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [transportclerk.actor :as actor]
            [transportclerk.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-vehicle! st {:vehicle-id "V-1" :client-id "client-1"
                                 :name "truck-7"
                                 :max-payload-kg 5000
                                 :approved-hazmat-classes #{"class-3"}})
    st))

(deftest commits-an-in-payload-authorized-manifest
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-manifest :stake :low
                 :vehicle-id "V-1" :total-weight-kg 4000 :hazmat-classes #{"class-3"}}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-over-payload-manifest
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-manifest :stake :low
                 :vehicle-id "V-1" :total-weight-kg 9000 :hazmat-classes #{}}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-approves-overweight-permit-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-overweight-permit :stake :high
                 :vehicle-id "V-1" :total-weight-kg 4000 :hazmat-classes #{}}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
