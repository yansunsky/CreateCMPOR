# CreateCMPOR

> Create × CompactMachines: Parallel Room Evaluation System
> Minecraft 1.21.1 · NeoForge 21.1.230 · Create 6.0.10-281 · CompactMachines 7.0.81
> Current version: **0.3.8**

## Introduction

CreateCMPOR is an addon that combines the **Stress system** of [Create](https://github.com/Creators-of-Create/Create) with the **Compact Machines** of [CompactMachines](https://www.curseforge.com/minecraft/mc-mods/compact-machines) into a "**parallel room evaluation → factory replication**" system: it automatically evaluates an entire production line inside a compact room, solidifies its input/output pattern into a standalone **Factory Block**, and reproduces the same throughput outside the compact space.

This mod is a **remake of [CompactMachinesPOR](https://www.curseforge.com/minecraft/mc-mods/compactmachinespor)** (parallel room evaluation + factory blocks), reimplemented as a Create addon for Minecraft 1.21.1.

Author: **yansunsky** (LGPL-3.0)

## How It Works

1. Build a production line inside a compact room. Place **Stress Input/Output blocks** (to measure stress during evaluation) and **Input/Output blocks** (as item/fluid ports).
2. Right-click the CompactMachines room machine with the **Launcher Stick** → the room freezes and its contents are cloned into an `eval_world` replica.
3. During evaluation, IO blocks activate automatically and real throughput is recorded:
   - Warmup (not counted) → baseline scan (S1) → sampling → final scan (S2), protected by an **S0 three-scan audit** against cheating.
4. The output pattern is decided as **RATE** (stable continuous flow) or **REPLAY** (recorded pulse playback).
5. The evaluator solidifies into a **Factory Block** that keeps reproducing the recorded pattern, connectable via hoppers and other logistics.
6. Right-click the factory with the Launcher Stick to revert it to the original CompactMachines machine.

### Parallel Space Input Block (multi-factory evaluation)

Place a **Parallel Space Input Block** (`parallel_input_block`) inside the room and configure its whitelist with items just like the normal input block. A single evaluation then runs **one branch per whitelisted item** (each branch exposes only that item during sampling), and upon completion **N factory blocks are solidified**, stacked vertically above the machine position. Overlapping blocks are destroyed (with drops) — but any unbreakable block (e.g. bedrock) is **detected before the evaluation even starts** and blocks it, since unbreakable blocks cannot be moved in survival. Every factory in the group shows its **room code** and **sibling count** in the goggle tooltip, and right-clicking any member reverts the whole group (all other members vanish without drops).

## Blocks

| Block | Description |
|-------|-------------|
| **Launcher Stick** (`launcher_stick`) | Right-click a room machine to start evaluation; right-click a factory to revert it. |
| **Factory Block** (`factory_block`) | The result of evaluation. Six-face shaft openings (toggle per face with a Wrench); reproduces the line in RATE/REPLAY mode with its own input/output buffers. |
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
| `suspiciousMods` | `["ae2","refinedstorage"]` | Mod IDs with unauditable storage blocks |
| `suspiciousBlocks` | `[]` | Block IDs forbidden from entering evaluation |
| `suspiciousItems` | `[]` | Item IDs forbidden from entering evaluation |
| `maxConcurrentEvaluations` | `4` | Max concurrent evaluation sessions cloning replicas |
| `evaluateSeconds` | `60` | Evaluation duration (seconds) |
| `recordStart` | `60` | Warmup seconds at evaluation start (not counted) |
| `evaluationMode` | `["AUTO"]` | Evaluation mode: AUTO / FORCE_RATE / FORCE_REPLAY |
| `lossRate` | `0.95` | Output loss guardrail (0-1; default 5% deduction) |
| `intermediateRatio` | `0.25` | Intermediate product filter ratio |
| `ioErrorRatio` | `0.001` | Dynamic noise threshold ratio |
| `catalystItems` | `[]` | Catalyst keep-list (kept as inputs even in small amounts) |
| `dedupBlocks` | Storage Drawers compacting drawers etc. | Block IDs to de-duplicate during inventory scan |
| `energyStabilityRelaxation` | `2.0` | Stability tolerance relaxation for energy entries |
| `stressStabilityRelaxation` | `2.0` | Stability tolerance relaxation for stress entries |
| `enableFactoryRevert` | `true` | Allow the launcher stick to revert the factory to the original machine |

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
