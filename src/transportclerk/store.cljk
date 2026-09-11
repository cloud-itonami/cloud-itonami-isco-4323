(ns transportclerk.store
  "SSoT for the ISCO-08 4323 community transport clerks actor
  (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors section).
  Modeled on cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client  — a registered organization (:client-id, :name)
    vehicle — a registered vehicle {:vehicle-id :client-id :name
              :max-payload-kg number
              :approved-hazmat-classes #{class-str}}.
              `:max-payload-kg` is the registered ceiling a proposed
              manifest's total weight must not exceed (payload is
              physics, not optimism); `:approved-hazmat-classes` is
              the registered set a proposed manifest's hazmat classes
              must all be members of (no unauthorized hazmat class on
              this vehicle).
    record  — a committed operating record (approved manifest) —
              written ONLY via commit-record!.
    ledger  — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (vehicle [s vehicle-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-vehicle! [s v])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (vehicle [_ vehicle-id] (get-in @a [:vehicles vehicle-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-vehicle! [s v]
    (swap! a assoc-in [:vehicles (:vehicle-id v)] v) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :vehicles {} :records [] :ledger []}
                                   seed)))))
