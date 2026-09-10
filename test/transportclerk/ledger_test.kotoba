(ns transportclerk.ledger-test
  (:require [clojure.test :refer [deftest is testing]]
            [transportclerk.ledger :as ledger]))

;; Every refusal below asserts the SENTENCE the ledger gave, not merely
;; that something threw. A negative test that only checks "it threw"
;; counts a run that failed for an unrelated reason as a discrimination.

(defn- fault-of
  "The ledger's stated reason for refusing `m`, or ::accepted."
  [m]
  (try (ledger/entry m) ::accepted
       (catch clojure.lang.ExceptionInfo e (:fault (ex-data e)))))

(def ^:private basis {:ceiling-kg 5000 :approved-hazmat-classes #{"class-3"}})

(defn- record
  ([] (record :approve-manifest 4000 #{"class-3"}))
  ([op kg classes]
   {:client-id "client-1" :op op :vehicle-id "V-1"
    :payload {:op op :effect :propose :vehicle-id "V-1"
              :total-weight-kg kg :hazmat-classes classes
              :stake :low :confidence 0.95}}))

(def ^:private verdict
  {:ok? false :hard? true :escalate? false :confidence 0.95
   :violations [{:rule :payload-exceeds-ceiling :detail "…"}]})

;; ---------------------------------------------------------------- accepts

(deftest accepts-a-governor-cleared-commit
  (let [e (ledger/entry {:disposition :commit :authorisation :governor-clear
                         :record (record) :basis basis})]
    (is (= :governor-clear (ledger/authorisation-of e)))
    (is (not (ledger/human-signed? e)))
    (is (= basis (:basis e)))))

(deftest accepts-a-human-signed-commit
  (let [e (ledger/entry {:disposition :commit :authorisation :human-sign-off
                         :record (record) :basis basis})]
    (is (ledger/human-signed? e))))

(deftest accepts-a-governor-hold
  (let [e (ledger/entry {:disposition :hold :authorisation :governor-hold
                         :verdict verdict})]
    (is (= :governor-hold (ledger/authorisation-of e)))
    (is (= [:payload-exceeds-ceiling] (mapv :rule (:violations (:verdict e)))))))

;; ------------------------------------------------------- shape refusals

(deftest refuses-an-unknown-disposition
  (is (re-find #":disposition は :commit か :hold"
               (fault-of {:disposition :maybe :authorisation :governor-clear
                          :record (record) :basis basis}))))

(deftest refuses-an-entry-that-does-not-name-its-authorisation
  (testing "the gap this namespace exists to close"
    (is (re-find #"何が許可したかを名乗らなければならない"
                 (fault-of {:disposition :commit :record (record) :basis basis})))))

(deftest refuses-an-unknown-authorisation
  (is (re-find #"未知" (fault-of {:disposition :commit :authorisation :vibes
                                  :record (record) :basis basis}))))

(deftest refuses-a-commit-authorised-by-a-hold
  (is (re-find #":commit を :governor-hold が許可することはない"
               (fault-of {:disposition :commit :authorisation :governor-hold
                          :record (record) :basis basis}))))

(deftest refuses-a-hold-claiming-human-sign-off
  (is (re-find #":hold の :authorisation は :governor-hold のみ"
               (fault-of {:disposition :hold :authorisation :human-sign-off
                          :verdict verdict}))))

(deftest refuses-a-commit-without-a-record
  (is (re-find #":commit には :record が要る"
               (fault-of {:disposition :commit :authorisation :governor-clear
                          :basis basis}))))

(deftest refuses-a-hold-without-a-verdict
  (is (re-find #":hold には拒否理由としての :verdict が要る"
               (fault-of {:disposition :hold :authorisation :governor-hold}))))

;; -------------------------------------------------- escalation refusals

(deftest refuses-an-overweight-permit-claiming-governor-clear
  (testing "the governor escalates this op unconditionally, so a
            :governor-clear row reports the rule has weakened"
    (is (re-find #"escalation 規則が緩んだ"
                 (fault-of {:disposition :commit :authorisation :governor-clear
                            :record (record :approve-overweight-permit 4000 #{})
                            :basis basis})))))

(deftest accepts-an-overweight-permit-a-human-signed
  (testing "the control for the refusal above: same op, human sign-off"
    (let [e (ledger/entry {:disposition :commit :authorisation :human-sign-off
                           :record (record :approve-overweight-permit 4000 #{})
                           :basis basis})]
      (is (ledger/human-signed? e)))))

;; ------------------------------------------------------- basis refusals

(deftest refuses-a-commit-without-a-basis
  (testing "a row that records no ceiling stops being readable once the
            vehicle register is overwritten"
    (is (re-find #":commit には :basis が要る"
                 (fault-of {:disposition :commit :authorisation :governor-clear
                            :record (record)})))))

(deftest refuses-a-basis-without-a-numeric-ceiling
  (is (re-find #":ceiling-kg が数値でない"
               (fault-of {:disposition :commit :authorisation :governor-clear
                          :record (record)
                          :basis {:ceiling-kg nil
                                  :approved-hazmat-classes #{}}}))))

(deftest refuses-a-basis-without-an-approved-class-set
  (is (re-find #":approved-hazmat-classes が要る"
               (fault-of {:disposition :commit :authorisation :governor-clear
                          :record (record)
                          :basis {:ceiling-kg 5000}}))))

(deftest refuses-a-manifest-above-its-own-recorded-ceiling
  (testing "HARD invariant payload-exceeds-ceiling makes this commit
            unreachable, so the row reports the invariant stopped holding"
    (let [f (fault-of {:disposition :commit :authorisation :governor-clear
                       :record (record :approve-manifest 9000 #{"class-3"})
                       :basis basis})]
      (is (re-find #"HARD 不変条件 payload-exceeds-ceiling" f))
      (is (re-find #"9000kg" f))
      (is (re-find #"5000kg" f)))))

(deftest accepts-an-overweight-permit-above-the-ceiling
  (testing "the control for the refusal above — an over-capacity load is
            exactly what this op requests, so the ceiling rule must NOT
            fire here. Same weight, same basis, different op."
    (let [e (ledger/entry {:disposition :commit :authorisation :human-sign-off
                           :record (record :approve-overweight-permit 9000 #{})
                           :basis basis})]
      (is (ledger/human-signed? e))
      (is (false? (ledger/within-recorded-ceiling? e))))))

(deftest refuses-a-class-outside-its-own-recorded-approved-set
  (let [f (fault-of {:disposition :commit :authorisation :governor-clear
                     :record (record :approve-manifest 4000 #{"class-3" "class-7"})
                     :basis basis})]
    (is (re-find #"HARD 不変条件 unauthorized-hazmat-class" f))
    (is (re-find #"class-7" f))))

;; ------------------------------------------------------------- readers

(deftest a-row-stays-readable-after-the-vehicle-is-re-registered
  (testing "the reason the basis is carried at all"
    (let [e (ledger/entry {:disposition :commit :authorisation :governor-clear
                           :record (record) :basis basis})]
      (is (true? (ledger/within-recorded-ceiling? e)))
      ;; the register now says 3000, but the row still answers for itself
      (is (= 5000 (get-in e [:basis :ceiling-kg]))))))

(deftest pre-namespace-rows-answer-nil-not-governor-clear
  (testing "absence of an authorisation is not evidence of an automatic one"
    (is (nil? (ledger/authorisation-of {:disposition :commit :record (record)})))
    (is (nil? (ledger/within-recorded-ceiling? {:disposition :commit
                                                :record (record)})))))

(deftest basis-of-an-unknown-vehicle-is-nil-not-a-zero-ceiling
  (is (nil? (ledger/basis-of nil)))
  (is (= {:ceiling-kg 5000 :approved-hazmat-classes #{"class-3"}}
         (ledger/basis-of {:vehicle-id "V-1" :max-payload-kg 5000
                           :approved-hazmat-classes #{"class-3"}}))))
