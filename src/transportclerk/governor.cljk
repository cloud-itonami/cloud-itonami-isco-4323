(ns transportclerk.governor
  "TransportClerksGovernor — the independent safety/traceability layer
  for the ISCO-08 4323 community transport clerks actor (itonami
  actor pattern, ADR-2607011000 / CLAUDE.md Actors section). Modeled
  on cloud-itonami-isco-4311's bookkeeping.governor. Manifest twist: a
  proposed manifest's total weight is arithmetic comparison against
  the registered payload ceiling — payload is physics, not optimism —
  and every proposed hazmat class must be a member of the registered
  approved set for that vehicle.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. vehicle basis        — a manifest approval must cite a
                           REGISTERED vehicle belonging to this
                           client.
    4. payload ceiling      — the proposed total-weight-kg must not
                           exceed the vehicle's registered
                           :max-payload-kg (payload is physics, not
                           optimism).
    5. hazmat-class subset  — every proposed hazmat class must be a
                           member of the vehicle's registered
                           :approved-hazmat-classes set (no
                           unauthorized hazmat class on this vehicle).
  ESCALATION invariants (:escalate? true, human sign-off):
    6. :op :approve-overweight-permit (special-permit exception
                           request for an over-capacity load).
    7. low confidence (< `confidence-floor`)."
  (:require [clojure.set :as set]
            [transportclerk.store :as store]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [request proposal]} client-record v]
  (let [{:keys [op total-weight-kg hazmat-classes]} proposal
        approve? (= :approve-manifest op)
        unauthorized (when v (set/difference (set hazmat-classes) (:approved-hazmat-classes v)))]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and approve? (nil? v))
      (conj {:rule :unknown-vehicle :detail "未登録 vehicle へのマニフェスト承認は不可"})

      (and approve? v (not= (:client-id v) (:client-id request)))
      (conj {:rule :vehicle-wrong-client :detail "vehicle が別 client のもの"})

      (and approve? v (number? total-weight-kg) (> total-weight-kg (:max-payload-kg v)))
      (conj {:rule :payload-exceeds-ceiling
             :detail (str "積載重量 " total-weight-kg "kg > 登録済み上限 "
                          (:max-payload-kg v) "kg（積載量は物理であって楽観ではない）")})

      (and approve? v (seq unauthorized))
      (conj {:rule :unauthorized-hazmat-class
             :detail (str "未承認危険物分類 " (vec unauthorized)
                          "（この車両への未承認危険物分類は許可されない）")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `transportclerk.store/Store`. Pure — never
  mutates the store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        v (some->> (:vehicle-id proposal) (store/vehicle store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record v)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (= :approve-overweight-permit (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
