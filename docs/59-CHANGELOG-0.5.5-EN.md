# CreateCMPOR 0.5.5 — Changelog (English)

**Fixes a rare but hard crash related to chunk unloading.**

---

## What was happening

Under certain conditions the server could crash with:

```
java.lang.ArrayIndexOutOfBoundsException: Index 512 out of bounds for length 129
    at ...PersistentEntitySectionManager.processUnloads(...)
```

This is a crash inside Minecraft's chunk-unloading code, not in this mod. It is caused by a
mismatch between how vanilla walks its "chunks waiting to unload" set and how the fastutil
collection library shrinks that set while it is being walked. Two details combine:

* every element removal can silently halve the underlying table;
* `0L` is stored as a special "null key" in its own slot, so an iterator can hold an index
  that no longer exists after the table shrinks.

The one chunk that can trigger it is chunk `(0, 0)` — and Compact Machines' room grid is
anchored exactly there, so `compactmachines:compact_world` is where it surfaces.

## What we changed

This mod used to release the entire evaluation's chunk tickets in a single tick. With a large
room that is hundreds of chunks dropping out of view at once — which is precisely the kind of
burst that makes the vanilla collection grow large and then shrink rapidly.

Ticket release is now spread across ticks: **at most 32 chunks per tick**, one distance tier
at a time. For a typical 256-chunk room the release finishes in about 16 ticks (under a second),
and the per-tick churn stays in a range the vanilla code handles safely.

## Honest scope of this fix

**This reduces the chance of the crash. It does not eliminate it.**

The underlying defect lives in vanilla's unloading loop and in fastutil's shrink behaviour, and
can be reached by anything that drops a large number of chunks out of view in one go — including
simply teleporting out of a Compact Machines dimension yourself. We have removed *our* way of
causing that burst; we cannot fix everyone else's.

A proper fix belongs upstream (either fastutil refusing to shrink while an iterator is live, or
vanilla collecting the set before processing it). If you run into this crash again outside an
evaluation, it is worth reporting to the fastutil / NeoForge trackers.

## Side effects

None that you should notice. Ticket release happening over several ticks means the cleanup
phase may take a fraction of a second longer to finish for large rooms.

## Upgrading

Drop in the new jar. **No new world needed** — no save format changed.

---

## Notes

* The evaluation cleanup is idempotent, so a save interrupted mid-release simply redoes the
  remaining work on the next run. Nothing is left half-released.
* Verified by logic simulation across room sizes from 0 to 256 chunks (per-tick release is
  always ≤ 32, both distance tiers fully covered, no duplicates or gaps), static analysis, and
  a clean build. **Not yet verified in-game** — please report if you still see this crash after
  updating, ideally with the room size and what you were doing at the time.
