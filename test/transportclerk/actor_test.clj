(ns transportclerk.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [transportclerk.actor :as actor]
            [transportclerk.advisor :as advisor]
            [transportclerk.ledger :as ledger]
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

;; ---------------------------------------------------------------- ledger
;; Measured on bae79e8: an automatic :approve-manifest and one a human
;; signed off after a low-confidence escalation wrote rows carrying
;; exactly (:disposition :record), differing only in the weight and in
;; the advisor's self-reported :confidence. These fix that.

(defn- low-confidence-advisor
  "Reports a confidence below governor/confidence-floor, so an ordinary
  :approve-manifest takes the escalation path."
  []
  (reify advisor/Advisor
    (-advise [_ _ req]
      {:op (:op req) :effect :propose :vehicle-id (:vehicle-id req)
       :total-weight-kg (:total-weight-kg req)
       :hazmat-classes (:hazmat-classes req)
       :stake :high :confidence 0.3 :rationale "unsure"})))

(deftest an-automatic-commit-is-recorded-as-governor-cleared
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})]
    (actor/run-request! graph {:client-id "client-1" :op :approve-manifest :stake :low
                               :vehicle-id "V-1" :total-weight-kg 4000
                               :hazmat-classes #{"class-3"}} {} "led-1")
    (let [e (first (store/ledger st))]
      (is (= :governor-clear (ledger/authorisation-of e)))
      (is (not (ledger/human-signed? e))))))

(deftest a-resumed-commit-is-recorded-as-human-signed
  (let [st (fresh-store)
        graph (actor/build-graph {:store st :advisor (low-confidence-advisor)})
        r (actor/run-request! graph {:client-id "client-1" :op :approve-manifest
                                     :stake :high :vehicle-id "V-1"
                                     :total-weight-kg 4100
                                     :hazmat-classes #{"class-3"}} {} "led-2")]
    (is (= :interrupted (:status r)))
    (is (empty? (store/ledger st)) "nothing is written before the human resumes")
    (actor/approve! graph "led-2")
    (let [e (first (store/ledger st))]
      (is (ledger/human-signed? e)))))

(deftest the-two-same-op-commits-are-now-distinguishable
  (testing "the defect this component was built for: both rows are
            :approve-manifest, one cleared by the governor and one signed
            by a human, and before :authorisation nothing told them apart"
    (let [st (fresh-store)
          auto (actor/build-graph {:store st})
          low  (actor/build-graph {:store st :advisor (low-confidence-advisor)})]
      (actor/run-request! auto {:client-id "client-1" :op :approve-manifest :stake :low
                                :vehicle-id "V-1" :total-weight-kg 4000
                                :hazmat-classes #{"class-3"}} {} "led-3a")
      (actor/run-request! low {:client-id "client-1" :op :approve-manifest :stake :high
                               :vehicle-id "V-1" :total-weight-kg 4100
                               :hazmat-classes #{"class-3"}} {} "led-3b")
      (actor/approve! low "led-3b")
      (let [[a b] (store/ledger st)]
        (is (= :approve-manifest (get-in a [:record :op]) (get-in b [:record :op])))
        (is (= [:governor-clear :human-sign-off]
               [(ledger/authorisation-of a) (ledger/authorisation-of b)]))
        (is (= 1 (count (filter ledger/human-signed? (store/ledger st)))))))))

(deftest a-hold-is-recorded-as-a-governor-hold
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})]
    (actor/run-request! graph {:client-id "client-1" :op :approve-manifest :stake :low
                               :vehicle-id "V-1" :total-weight-kg 9000
                               :hazmat-classes #{}} {} "led-4")
    (let [e (first (store/ledger st))]
      (is (= :governor-hold (ledger/authorisation-of e)))
      (is (some #(= :payload-exceeds-ceiling (:rule %))
                (:violations (:verdict e)))))))

(deftest a-committed-row-survives-the-vehicle-being-re-registered
  (testing "measured on bae79e8: re-registering V-1 from 5000kg down to
            3000kg left an already-committed 4000kg manifest reading
            exactly as before, so whether it was within its ceiling when
            committed became unanswerable"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st})]
      (actor/run-request! graph {:client-id "client-1" :op :approve-manifest :stake :low
                                 :vehicle-id "V-1" :total-weight-kg 4000
                                 :hazmat-classes #{"class-3"}} {} "led-5")
      (is (true? (ledger/within-recorded-ceiling? (first (store/ledger st)))))
      (store/register-vehicle! st {:vehicle-id "V-1" :client-id "client-1"
                                   :name "truck-7" :max-payload-kg 3000
                                   :approved-hazmat-classes #{}})
      (is (= 3000 (:max-payload-kg (store/vehicle st "V-1"))))
      (let [e (first (store/ledger st))]
        (is (= 5000 (get-in e [:basis :ceiling-kg]))
            "the row answers for the ceiling that was in force when it committed")
        (is (true? (ledger/within-recorded-ceiling? e)))))))
