(ns transportclerk.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [transportclerk.store :as store]
            [transportclerk.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-vehicle! st {:vehicle-id "V-1" :client-id "client-1"
                                 :name "truck-7"
                                 :max-payload-kg 5000
                                 :approved-hazmat-classes #{"class-3" "class-9"}})
    st))

(defn- manifest [weight classes]
  {:op :approve-manifest :effect :propose :vehicle-id "V-1"
   :total-weight-kg weight :hazmat-classes classes :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-within-payload-and-approved-hazmat
  (let [st (fresh-store)
        v (governor/check req {} (manifest 4000 #{"class-3"}) st)]
    (is (:ok? v))))

(deftest ok-at-exact-payload-ceiling
  (testing "weight exactly at the ceiling is within margin"
    (let [st (fresh-store)
          v (governor/check req {} (manifest 5000 #{}) st)]
      (is (:ok? v)))))

(deftest hard-on-payload-exceeds-ceiling
  (testing "payload is physics, not optimism"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (manifest 8000 #{}) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :payload-exceeds-ceiling (:rule %)) (:violations v))))))

(deftest hard-on-unauthorized-hazmat-class
  (let [st (fresh-store)
        v (governor/check req {} (manifest 4000 #{"class-7"}) st)]
    (is (:hard? v))
    (is (some #(= :unauthorized-hazmat-class (:rule %)) (:violations v)))))

(deftest hard-on-unknown-vehicle
  (let [st (fresh-store)
        v (governor/check req {} (assoc (manifest 4000 #{}) :vehicle-id "V-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-vehicle (:rule %)) (:violations v)))))

(deftest hard-on-foreign-vehicle
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (let [v (governor/check {:client-id "client-2"} {} (manifest 4000 #{}) st)]
      (is (:hard? v))
      (is (some #(= :vehicle-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (manifest 4000 #{}) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (manifest 4000 #{}) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest escalates-overweight-permit
  (let [st (fresh-store)
        v (governor/check req {} {:op :approve-overweight-permit :effect :propose
                                  :vehicle-id "V-1" :confidence 0.9 :stake :high} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (manifest 4000 #{}) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
