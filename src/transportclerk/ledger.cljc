(ns transportclerk.ledger
  "Audit entries for the ISCO-08 4323 community transport clerks actor.

  Measured on bae79e8, before this namespace existed. Runs through
  `transportclerk.actor` wrote rows carrying exactly
  `(:disposition :record)` on every commit and `(:disposition :verdict)`
  on every hold. Two of those commits were both `:approve-manifest`:

    {:disposition :commit :record {... :total-weight-kg 4000 ...}}  automatic
    {:disposition :commit :record {... :total-weight-kg 4100 ...}}  human-signed

  The first was cleared by the governor alone. The second interrupted at
  `:request-approval` on low confidence and was resumed through
  `actor/approve!` — a human signed it. The rows differ only in the
  weight and in the `:confidence` the advisor reported about itself,
  which is the advisor's claim, not evidence that anybody resumed the
  thread. Asked which committed manifests a human had approved, the
  ledger could not answer.

  So an entry names its `:authorisation`:

    :governor-clear  the governor returned :ok? true; no human involved.
    :human-sign-off  the run interrupted at :request-approval and a
                     human resumed the thread. The act of resuming IS
                     the approval, so it is recorded as one.
    :governor-hold   the governor refused; nothing was committed.

  The second gap is specific to transport rather than to the pattern.
  This actor's two HARD invariants are arithmetic and subset
  containment against the REGISTERED vehicle record — total weight
  against `:max-payload-kg`, proposed hazmat classes against
  `:approved-hazmat-classes`. That registration is mutable:
  `store/register-vehicle!` overwrites it. Measured on the same commit,
  no row mentioned either field, so re-registering V-1 from 5000 kg down
  to 3000 kg left an already-committed 4000 kg manifest reading exactly
  as before. Whether that manifest was within its ceiling when it was
  committed became unanswerable — and the ceiling is the whole question
  a transport compliance ledger is kept for.

  So a commit entry also carries the `:basis` that was in force when the
  governor cleared it:

    {:ceiling-kg n :approved-hazmat-classes #{class}}

  which makes the row re-checkable on its own terms, whatever the
  vehicle register says later.

  Two refusals follow from carrying the basis, and they are the reason
  to carry it. An `:approve-manifest` entry recording a weight above its
  own `:ceiling-kg`, or a class outside its own approved set, describes
  a commit the governor's HARD rules make unreachable; it is not a
  mis-keyed row but a report that a HARD invariant has stopped holding.
  `:approve-overweight-permit` is deliberately exempt from the weight
  refusal — an over-capacity load is precisely what that op requests,
  which is why `transportclerk.governor` escalates it unconditionally
  instead of checking it. That same exemption is why an
  `:approve-overweight-permit` claiming `:governor-clear` is refused: it
  cannot reach `:commit` except through a human.

  `entry` is total and pure: it either returns a well-formed entry or
  throws, and it never reaches a store. Building an entry is not
  appending one.")

(def authorisations #{:governor-clear :human-sign-off :governor-hold})

(def ^{:doc "Operations that can never be committed on the governor's
  word alone. `transportclerk.governor` escalates
  `:approve-overweight-permit` unconditionally (README; `risky-op?`),
  so a human is the only path to `:commit`."}
  human-only-ops
  #{:approve-overweight-permit})

(def ^{:doc "Operations whose weight the governor checks against the
  registered ceiling. `:approve-overweight-permit` is absent by design —
  it exists to request an exception to that ceiling."}
  ceiling-checked-ops
  #{:approve-manifest})

(defn- basis-fault
  "Why this commit entry's recorded basis fails to justify it, as a
  sentence — or nil if it does."
  [{:keys [ceiling-kg approved-hazmat-classes] :as basis} record]
  (let [{:keys [op]} record
        {:keys [total-weight-kg hazmat-classes]} (:payload record)
        unauthorized (remove (set approved-hazmat-classes) (set hazmat-classes))]
    (cond
      (nil? basis)
      (str ":commit には :basis が要る — 照合した上限と承認済み分類を"
           "記録しない項目は、車両登録が書き換わった時点で読めなくなる")

      (not (number? ceiling-kg))
      (str ":basis の :ceiling-kg が数値でない（受領: " (pr-str ceiling-kg) "）")

      (nil? approved-hazmat-classes)
      ":basis に :approved-hazmat-classes が要る"

      (and (ceiling-checked-ops op)
           (number? total-weight-kg)
           (> total-weight-kg ceiling-kg))
      (str (pr-str op) " が積載重量 " total-weight-kg
           "kg を自ら記録した上限 " ceiling-kg "kg より上で commit されている"
           " — 台帳の誤記ではなく、HARD 不変条件 payload-exceeds-ceiling が"
           "効かなくなったという報告である")

      (seq unauthorized)
      (str "危険物分類 " (vec (sort unauthorized))
           " が自ら記録した承認集合 " (vec (sort approved-hazmat-classes))
           " の外にある — 台帳の誤記ではなく、HARD 不変条件"
           " unauthorized-hazmat-class が効かなくなったという報告である"))))

(defn- fault
  "Why this entry is ill-formed, as a sentence — or nil if it is not."
  [{:keys [disposition authorisation record verdict basis]}]
  (cond
    (not (#{:commit :hold} disposition))
    (str ":disposition は :commit か :hold（受領: " (pr-str disposition) "）")

    (not (authorisations authorisation))
    (str ":authorisation が無い、または未知（受領: " (pr-str authorisation)
         "、既知: " (pr-str (sort authorisations)) "）"
         " — 台帳の項目は、その書き込みを何が許可したかを名乗らなければならない")

    (and (= :commit disposition) (= :governor-hold authorisation))
    ":commit を :governor-hold が許可することはない"

    (and (= :hold disposition) (not= :governor-hold authorisation))
    (str ":hold の :authorisation は :governor-hold のみ（受領: "
         (pr-str authorisation) "）")

    (and (= :commit disposition) (nil? record))
    ":commit には :record が要る"

    (and (= :hold disposition) (nil? verdict))
    ":hold には拒否理由としての :verdict が要る"

    (and (= :commit disposition)
         (= :governor-clear authorisation)
         (human-only-ops (:op record)))
    (str (pr-str (:op record))
         " は上限超過の例外申請なので governor 単独では commit できない"
         "（README / governor の risky-op?）—"
         " :governor-clear を名乗る項目は、台帳の誤記ではなく"
         " escalation 規則が緩んだという報告である")

    (= :commit disposition)
    (basis-fault basis record)))

(defn entry
  "Build one audit entry. Throws on anything ill-formed — a ledger that
  accepts an entry it cannot interpret is worse than one that refuses,
  because the refusal is visible and the bad entry is not."
  [{:keys [disposition authorisation record verdict basis] :as m}]
  (when-let [f (fault m)]
    (throw (ex-info (str "ill-formed ledger entry: " f) {:entry m :fault f})))
  (cond-> {:disposition   disposition
           :authorisation authorisation}
    record  (assoc :record record)
    basis   (assoc :basis basis)
    verdict (assoc :verdict (select-keys verdict
                                         [:ok? :hard? :escalate? :confidence :violations]))))

(defn basis-of
  "The ceiling and approved-class set a vehicle record carried when the
  governor read it. `nil` vehicle — the governor refuses those before
  any commit — yields nil, so the caller cannot mistake absence for a
  zero ceiling."
  [vehicle]
  (when vehicle
    {:ceiling-kg              (:max-payload-kg vehicle)
     :approved-hazmat-classes (:approved-hazmat-classes vehicle)}))

(defn human-signed?
  "Did a human sign this entry off? The question the ledger exists to
  answer, asked of one entry."
  [e]
  (= :human-sign-off (:authorisation e)))

(defn authorisation-of
  "What authorised this write? nil for entries written before this
  namespace existed — which is the honest answer for them, and is
  deliberately not conflated with :governor-clear."
  [e]
  (:authorisation e))

(defn within-recorded-ceiling?
  "Was this commit within the ceiling IT recorded — not the ceiling the
  vehicle register happens to hold now? nil when the entry carries no
  basis, which is the honest answer for pre-`:basis` rows."
  [e]
  (when-let [c (get-in e [:basis :ceiling-kg])]
    (let [w (get-in e [:record :payload :total-weight-kg])]
      (when (number? w) (<= w c)))))
