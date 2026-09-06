# cloud-itonami-isco-4323

Open Business Blueprint for **ISCO-08 4323**: Transport Clerks — an ISCO
**Wave 0 (cognitive substrate)** occupation per ADR-2607121000:
pure-cognitive work, the LLM-first wave, **no robotics gate** —
eligible for actor implementation now.

**Maturity: `:implemented`** — TransportClerksAdvisor ⊣
TransportClerksGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
39 tests / 72 assertions green.

The manifest HARD invariants — arithmetic and subset containment, not
optimism:

1. **Payload ceiling** — a proposed manifest's total weight must not
   exceed the vehicle's registered maximum payload (payload is
   physics, not optimism).
2. **Hazmat-class subset** — every proposed hazmat class must be a
   member of the vehicle's registered approved-hazmat-classes set (no
   unauthorized hazmat class on this vehicle).

Also HARD: unregistered/foreign vehicle, unregistered organization,
non-`:propose` effect. Escalations (always human sign-off):
`:approve-overweight-permit` (special-permit exception request), low
confidence (< 0.6).

## The audit ledger names what authorised each write

Measured on `bae79e8`: every commit row carried exactly
`(:disposition :record)`. Two of them were both `:approve-manifest` —
one cleared by the governor alone, one interrupted at
`:request-approval` on low confidence and resumed by a human — and the
rows differed only in the weight and in the `:confidence` the advisor
reported **about itself**. Asked which committed manifests a human had
approved, the ledger could not answer.

`transportclerk.ledger` gives every entry an `:authorisation`:
`:governor-clear`, `:human-sign-off`, or `:governor-hold`.

It also carries the **basis** the governor checked against. Both HARD
invariants are comparisons against the *registered* vehicle record, and
that registration is mutable — `store/register-vehicle!` overwrites it.
Measured on the same commit, no row mentioned `:max-payload-kg` or
`:approved-hazmat-classes`, so re-registering `V-1` from 5000 kg down to
3000 kg left an already-committed 4000 kg manifest reading exactly as
before. A commit entry now records
`{:ceiling-kg n :approved-hazmat-classes #{class}}`, so the row answers
for the ceiling that was in force when it committed, whatever the
register says later.

`ledger/entry` refuses rather than storing a row it cannot interpret —
including two refusals that only the basis makes possible. An
`:approve-manifest` above its own recorded ceiling, or carrying a class
outside its own recorded approved set, describes a commit the HARD rules
make unreachable; it is not a mis-keyed row but a report that a HARD
invariant has stopped holding. `:approve-overweight-permit` is exempt
from the weight refusal — an over-capacity load is exactly what that op
requests — and for the same reason an `:approve-overweight-permit`
claiming `:governor-clear` is refused: it cannot reach `:commit` except
through a human.

Rows written before this namespace existed answer `nil`, which is the
honest answer for them and is deliberately not conflated with
`:governor-clear`.



AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
