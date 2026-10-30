# CreateCMPOR

> Create × CompactMachines: Parallel Room Evaluation System
> Minecraft 1.21.1 · NeoForge 21.1.x · Create 6.0.10-281 · CompactMachines 7.0.81
> Current version: **0.5.5**

## Introduction

CreateCMPOR is an addon that combines the **Stress system** of [Create](https://github.com/Creators-of-Create/Create) with the **Compact Machines** of [CompactMachines](https://www.curseforge.com/minecraft/mc-mods/compact-machines) into a "**parallel room evaluation → factory replication**" system: it automatically evaluates an entire production line inside a compact room, solidifies its input/output pattern into a standalone **Factory Block**, and reproduces the same throughput outside the compact space.

This mod is a **remake of [CompactMachinesPOR](https://www.curseforge.com/minecraft/mc-mods/compactmachinespor)** (parallel room evaluation + factory blocks), reimplemented as a Create addon for Minecraft 1.21.1.

Author: **yansunsky** (LGPL-3.0)

Every block ships with a **Ponder tutorial** (English & 简体中文); the factory block additionally shows its **three casing forms** and the full lifecycle.

## How It Works

1. Build a production line inside a compact room. Place **Stress Input/Output blocks** (to measure stress during evaluation) and **Input/Output blocks** (as item/fluid ports).
2. Right-click the CompactMachines room machine with the **Launcher Stick** → the room freezes and its contents are cloned into an `eval_world` replica.
3. During evaluation, IO blocks activate automatically and real throughput is recorded:
   - Warmup (not counted) → baseline scan (S1) → sampling → final scan (S2), protected by an **S0 three-scan audit** against cheating.
4. The output pattern is decided as **RATE** (stable continuous flow) or **REPLAY** (recorded pulse playback).
5. The evaluator solidifies into a **Factory Block** that keeps reproducing the recorded pattern, connectable via hoppers and other logistics.
6. Right-click the factory with the Launcher Stick to revert it to the original CompactMachines machine.

### Purification Launcher Stick (0.5.2+)

Rooms tend to carry leftovers from the last round of debugging. The three-scan audit has always had a "room starts completely empty → settle on measured flow alone" shortcut, but a single leftover item makes it unreachable.

The **Purification Launcher Stick** (red, five sticks in an inverted launcher pattern) clears container leftovers from the **replica** before the evaluation starts — the original room is never touched, only the throwaway copy. The run then settles through the empty-room path.

- Scope is set by the `[purification]` section: `purificationClearMode` = `WHITELIST` (default — Create logistics/processing buffers only) / `ALL` / `BLACKLIST`. Dropped items are always cleared.
- Filter items are never touched — they live in item components, not container inventories.
- If the room contains a moving structure (a Create contraption) or some container cannot be cleared, the run quietly falls back to a normal evaluation and says so in chat, rather than pretending it worked.
- `/ccmpor clean <roomCode> [execute]` — report-only by default (lists what *would* be cleared); add `execute` to actually do it. Works on the room you stand in, i.e. before anything is frozen or cloned.
- The stick comes back in the same variety you used (before 0.5.2 it downgraded to a normal launcher).
- `enablePurification = false` turns the feature off.

> **Known limitation:** blaze burner pre-stored heat is **not** cleared. If a room contains a burner that still has fuel, S0 stays non-empty, purification cannot take effect, and the run automatically falls back to the full evaluation with a chat notice. Let your burners burn out first.

### Parallel Space Input Block (multi-factory evaluation)

Place a **Parallel Space Input Block** (`parallel_input_block`) inside the room and configure its whitelist with items just like the normal input block. A single evaluation then runs **one branch per whitelisted item** (each branch exposes only that item during sampling), and upon completion **N factory blocks are solidified**, stacked vertically above the machine position. Overlapping blocks are destroyed (with drops) — but any unbreakable block (e.g. bedrock) is **detected before the evaluation even starts** and blocks it, since unbreakable blocks cannot be moved in survival. Every factory in the group shows its **room code** and **sibling count** in the goggle tooltip, and right-clicking any member reverts the whole group (all other members vanish without drops).

### Single Solidified Factory with Mutually Exclusive Modes (0.5.0+)

An evaluation now solidifies a **single** factory block, which carries an internal **mutually exclusive mode table**: at most one mode is producing at any moment, switching automatically based on inputs, fuel and output space. This preserves replication of multiple outputs while closing off "cheap junk + parallel block production-line duplication" exploits.

The goggles show which mode is currently active; mode changes are synced at most once per second, so machines alternating their inputs do not spam packets.

## Factory Miniature Preview (0.4.0+)

A solidified **Factory Block draws the whole production line inside itself as a working miniature**:

- **Three casing forms** (switching never consumes materials):
  - **Display form** (default): a 3px base, four corner posts, top rails and four glass panes — you can see the line through the glass;
  - Right-click with Create's **Andesite Casing** → classic casing look, all six faces can be opened with a Wrench as shaft interfaces;
  - Right-click with Create's **Framed Glass** → the built-in case comes off and only the base remains;
  - **Sneak + Wrench** steps back one form at a time (case → casing → display form); once nothing is left to take off,
    sneak-wrench picks the factory up (the drop carries the full block-entity data, so it can be rebuilt elsewhere).
- **What is rendered**: blocks are drawn with their real blockstates; rotating parts (shafts, gears, cogs, crushing wheels,
  grindstones, gearboxes, clutches, creative motors, water wheels, flywheels, turntables, encased fans, mechanical pumps,
  mechanical drills and more) **really rotate**; rotating contraptions such as mechanical/windmill bearings **turn together
  with the whole structure they carry**; mobs, dropped items and item frames inside the room show up as well.
- **Item preview**: the factory item in your hand and in inventories / GUIs / JEI shows its own miniature.
- **Bandwidth**: snapshots are **fetched on demand** by default — a client asks once when it actually renders the factory,
  then caches it per revision, instead of receiving it with every block-entity update (`FULL` mode is still available).
- **Size guard**: when one factory item's block-entity NBT exceeds `previewItemMaxKb`, it is trimmed step by step at the moment
  it is dropped (entity/contraption section first, then the whole preview) so it can never exceed the client's 2 MB in-packet
  NBT quota and disconnect the receiver.
- Diagnostics: `/ccmpor preview info | entities | bumprev` on the server, `/ccmporc preview sync` on the client.

## Blocks

| Block | Description |
|-------|-------------|
| **Launcher Stick** (`launcher_stick`) | Right-click a room machine to start evaluation; right-click a factory to revert it. |
| **Purification Launcher Stick** (`purification_stick`) | Right-click a room machine to start evaluation, but **clears container leftovers from the replica first** so the run settles through the empty-room path. The original room is untouched. See above. |
| **Factory Block** (`factory_block`) | The result of evaluation. Default display form renders a working **miniature production line** inside the block (see above). Right-click with Andesite Casing for the classic casing look (six shaft faces, toggled with a Wrench), or with Framed Glass to remove the built-in case. Reproduces the line in RATE/REPLAY mode with its own input/output buffers and an internal mutually exclusive mode table. |
| **IO Extension Block** (`io_extension`) | Connects to the Create stress network like a normal shaft. **Axial ends** are stress interface faces (attach to factory openings / shafts / other extensions); **non-axial sides** forward the factory's item/fluid/energy buffers (input + output) reachable along the axial chain (pointing directly at the factory's own buffers, no item duplication). If both axial ends connect to two different factory buffers, it self-destructs to prevent duplication exploits. |
| **Parallel Space Input Block** (`parallel_input_block`) | Like the input block, but each whitelisted item becomes one evaluation branch, producing one factory per item (stacked vertically). |
| **Stress Input Block** (`stress_input`) | Inside the room; activates during evaluation as a stress source to measure real stress consumption. Right-click with an empty hand (creative) or use the scroll wheel on the slider box to adjust speed and direction (-256 to +256 RPM, default 16). |
| **Stress Output Block** (`stress_output`) | Inside the room; activates during evaluation as a passive observer of available network stress. |
| **Input Block** (`input_block`) | Room port: right-click with an item/bucket to set a whitelist; feeds whitelisted items during evaluation. |
| **Output Block** (`output_block`) | Room port: right-click with an item/bucket to set a whitelist; collects whitelisted products during evaluation. |
| **Evaluator Block** (`evaluator_block`) | Intermediate state during evaluation (freezing/evaluating/solidifying); solidifies into a factory. |

## Configuration (`config/createcmpor-common.toml`)

| Key | Default | Description |
|-----|---------|-------------|
| `enableStressOutput` | `true` | Allow stress I/O blocks to provide stress outward |
| `devManualActivation` | `false` | Dev-only: allow empty-hand right-click manual toggle of stress I/O blocks (off in production) |
| `stressLossFactor` | `0.1` | Stress output loss factor (0~1) |
| `enableInventoryAudit` | `true` | Enable inventory conservation audit before/after evaluation |
| `suspiciousMods` | `[]` | Mod IDs with unauditable storage blocks (**empty by default**; add your own) |
| `suspiciousBlocks` | `[]` | Block IDs forbidden from entering evaluation |
| `suspiciousItems` | `[]` | Item IDs forbidden from entering evaluation |
| `maxConcurrentEvaluations` | `4` | Max concurrent evaluation sessions cloning replicas |
| `evaluateSeconds` | `60` | Evaluation duration (seconds) |
| `recordStart` | `60` | Warmup seconds at evaluation start (not counted) |
| `evaluationMode` | `["AUTO"]` | Evaluation mode: AUTO / FORCE_RATE / FORCE_REPLAY |
| `lossRate` | `0.95` | Output loss guardrail (0-1; default 5% deduction) |
| `intermediateRatio` | `0.25` | Intermediate product filter ratio (**unified across RATE and REPLAY since 0.5.1**) |
| `ioErrorRatio` | `0.001` | Dynamic noise threshold ratio |
| `catalystItems` | `[]` | Catalyst keep-list (kept as inputs even in small amounts) |
| `dedupBlocks` | Storage Drawers compacting drawers etc. | Block IDs to de-duplicate during inventory scan |
| `energyStabilityRelaxation` | `2.0` | Stability tolerance relaxation for energy entries |
| `stressStabilityRelaxation` | `2.0` | Stability tolerance relaxation for stress entries |
| `continueOnSourceReloaded` | `false` | Keep evaluating after the source room's chunks unload/reload |
| `enableEvaluationObservation` | `true` | Enable observer supervision in the evaluation dimension (auto-exit when flying outside) |
| `observationPermissionLevel` | `0` | Permission level allowed to freely enter evaluation replicas as an observer |
| `enableFactoryRevert` | `true` | Allow the launcher stick to revert the factory to the original machine |

> **Changed in 0.4.28:** `unloadStuckPendingTicks` has been **removed**. Its "release after N ticks" timer was the main source of freeze-phase failures — it relaxed the wait-for-unload timer without relaxing the strict re-check, so a released chunk was inevitably rejected right after. Freeze gating is now decided purely by whether each chunk's contents have actually unloaded; there is no tick-based exemption.

### Purification Launcher Stick (0.5.2+)

| Key | Default | Description |
|-----|---------|-------------|
| `enablePurification` | `true` | Enable the purification feature (when off, the stick behaves as a normal launcher) |
| `purificationClearMode` | `WHITELIST` | Clear scope: `WHITELIST` / `ALL` / `BLACKLIST` |
| `purificationWhitelist` | Create logistics/processing buffers | Block IDs cleared in WHITELIST mode |
| `purificationBlacklist` | `[]` | Block IDs exempted from clearing in BLACKLIST mode |
| `purificationFallbackOnContraption` | `true` | Fall back to a normal evaluation when a moving structure is detected |


### Miniature Preview (0.4.0+; KB values are NBT sizes)

| Key | Default | Description |
|-----|---------|-------------|
| `enableFactoryPreview` | `true` | Capture and render the miniature inside the block |
| `enableFactoryItemPreview` | `true` | Show the miniature on the item in hand / inventories / GUIs |
| `previewMaxVolume` | `8192` | Max room volume captured (blocks) |
| `factoryPreviewAnimationSeconds` | `4` | Animation period of one rotation (seconds; 0 = static) |
| `previewMaxEntities` / `previewMaxContraptions` | `48` / `8` | Max normal / contraption entity entries per snapshot |
| `previewEntityMaxKb` / `previewContraptionMaxKb` | `8` / `192` | Per-entity / per-contraption NBT budget (per-key trimming, never whole-entity drops) |
| `previewEntityTableMaxKb` | `1024` | Total budget of the whole entity table |
| `previewItemMaxKb` | `1024` | Item-side size guard (trimmed progressively when exceeded) |
| `previewSyncMode` | `ON_DEMAND` | Snapshot delivery: `FULL` (with block-entity packets) / `ON_DEMAND` (client requests) |
| `previewRequestMode` | `ON_DEMAND` | Whether the client requests snapshots (sends nothing if the server does not support it) |
| `previewSyncMaxKb` | `1024` | Max size of a single sync response payload |
| `previewCacheMaxKb` | `16384` | Client snapshot cache soft cap (LRU eviction) |

## Developer Commands (OP level 2)

All commands live under `/ccmpor` (OP only):

| Command | Description |
|---------|-------------|
| `/ccmpor room code` | Show the current room code (click to copy) |
| `/ccmpor room enter original <room>` | Teleport into the original room |
| `/ccmpor room enter eval <room>` | Teleport into the eval_world replica at the same coordinates |
| `/ccmpor room diff <room>` | Compare persisted chunk differences between original and replica |
| `/ccmpor evaluation list` | List in-progress evaluation sessions |
| `/ccmpor evaluation max` | Show the current concurrent evaluation limit |
| `/ccmpor evaluation max <value>` | Set the concurrent evaluation limit (1-16, effective until restart) |
| `/ccmpor clean <room> [execute]` | Purification: **report-only** by default (lists what would be cleared); add `execute` to actually clear |
| `/ccmpor factory legacy list` | List legacy factories left over from older versions (`compactmachinespor:factory_block`) |
| `/ccmpor factory legacy remove <pos>` | Remove a specified legacy factory |
| `/ccmpor factory legacy revert <pos>` | Revert a legacy factory to its original machine |
| `/ccmpor preview info` | Show preview state of a nearby factory (size, revision, entity count) |
| `/ccmpor preview entities` | List each entity's size and its largest NBT keys in the snapshot (debugging trims) |
| `/ccmpor preview bumprev` | Bump the newest factory's snapshot revision and force one on-demand round trip (debug) |
| `/ccmporc preview sync` | **Client**: cache entries/bytes/hits and request state |

## Building & Running

Requires **Java 21**.

```bash
# Build
./gradlew build

# Run client
./gradlew runClient

# Run server
./gradlew runServer
```

Dependencies are fetched from Maven:
- Create / Ponder / Flywheel: `https://maven.createmod.net`
- Registrate: `https://maven.ithundxr.dev/snapshots`
- CompactMachines: CurseForge (`https://cursemaven.com`)

## References & License

This mod is **remade from [CompactMachinesPOR](https://www.curseforge.com/minecraft/mc-mods/compactmachinespor)** and developed with reference to the following projects (design ideas only, reimplemented in our own structure, no code copied):

- **CompactMachinesPOR** — the direct predecessor; parallel room evaluation and factory block mechanics are rebuilt from it.
- **Create** — stress system, shaft rendering, andesite casing & creative motor implementation; models/textures are referenced via the `create:` namespace rather than copied.
- **CompactMachines** — compact rooms, machine blocks and room template mechanics.

License:
- **Code**: LGPL-3.0
- **Resources**: Models only reference Create's namespace resources; no third-party texture PNGs are bundled. Original resources default to ARR (All Rights Reserved).
