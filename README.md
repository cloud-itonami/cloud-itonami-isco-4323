# cloud-itonami-isco-4323

Open Business Blueprint for **ISCO-08 4323**: Transport Clerks — an ISCO
**Wave 0 (cognitive substrate)** occupation per ADR-2607121000:
pure-cognitive work, the LLM-first wave, **no robotics gate** —
eligible for actor implementation now.

**Maturity: `:implemented`** — TransportClerksAdvisor ⊣
TransportClerksGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
13 tests / 27 assertions green.

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



AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
