# CreateCMPOR

> Create（机械动力）× CompactMachines 平行房间评估系统
> Minecraft 1.21.1 · NeoForge 21.1.230 · Create 6.0.10-281 · CompactMachines 7.0.81

## 简介

CreateCMPOR 是一个附属模组，把 [Create（机械动力）](https://github.com/Creators-of-Create/Create) 的**应力系统**与 [CompactMachines](https://www.curseforge.com/minecraft/mc-mods/compact-machines) 的**压缩房间**整合成一套「**平行房间评估 → 工厂复刻**」系统：对压缩房间内的整条机器产线做一次自动化评估，把它的输入/输出模式固化成一个可独立运转的**工厂方块**，在压缩空间之外复刻同样的产能。

作者：**yansunsky**（LGPL-3.0）

## 玩法流程

1. 在压缩房间里搭好产线，放置**应力输入/输出方块**（供评估期测量应力）和**输入/输出方块**（作为物品/流体进出口）。
2. 手持**平行评估启动棒**右键房间的 CompactMachines 机器 → 房间冻结，内容克隆到 `eval_world` 副本。
3. 评估期自动激活 IO 方块，按真实流量记录：
   - 预热（不计入结果）→ 基线扫描（S1）→ 采样 → 终态扫描（S2），配合 **S0 三扫描审计**防刷。
4. 判定产出模式：**RATE**（稳定速率连续流）或 **REPLAY**（脉冲录制回放）。
5. 评估方块固化为**平行工厂方块**：按录得模式持续吞吐，可通过漏斗等物流接入。
6. 手持启动棒右键工厂，可还原为原 CompactMachines 机器。

## 方块

| 方块 | 说明 |
|------|------|
| **平行评估启动棒**（`launcher_stick`） | 右键房间机器开始评估；右键工厂可还原为原机器。 |
| **平行工厂方块**（`factory_block`） | 评估结果落点。六面开口（用扳手按面开关应力接口），按 RATE/REPLAY 模式复刻产线，自带输入/输出缓存。 |
| **IO 拓展方块**（`io_extension`） | 像普通传动轴一样接入 Create 应力网络。**轴向两端**为应力接口面（贴工厂开口面/接传动杆/轴向相连其他拓展方块）；**非轴向四面**把链上触达的工厂的物品/流体/能量缓存（输入+输出）拓展到此处（直接指向工厂自身缓存，不复制物品）。若轴向两端同时连到两个工厂缓存，自动破坏自身防止物品复制漏洞。 |
| **应力输入方块**（`stress_input`） | 房间内，评估期激活作为应力源，驱动内部机器以测得真实应力消耗。 |
| **应力输出方块**（`stress_output`） | 房间内，评估期激活作为被动观察者，记录网络可提供的剩余应力。 |
| **输入方块**（`input_block`） | 房间进出口：用物品/流体容器右键设置白名单，评估期激活后按白名单供料。 |
| **输出方块**（`output_block`） | 房间进出口：用物品/流体容器右键设置白名单，评估期激活后收集白名单产物送出。 |
| **评估方块**（`evaluator_block`） | 评估过程中的中间状态（冻结/评估/固化），结束后固化为工厂。 |

## 配置（`config/createcmpor-common.toml`）

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `enableStressOutput` | `true` | 是否允许应力 IO 方块向外提供应力 |
| `devManualActivation` | `false` | 开发期是否允许空手右键手动切换应力 IO 方块（生产默认关闭） |
| `stressLossFactor` | `0.1` | 应力输出损耗系数（0~1） |
| `enableInventoryAudit` | `true` | 是否启用评估前后库存守恒审计 |
| `suspiciousMods` | `["ae2","refinedstorage"]` | 不可审计存储方块的模组 ID |
| `suspiciousBlocks` | `[]` | 明确禁止进入评估的方块 ID |
| `suspiciousItems` | `[]` | 明确禁止进入评估的物品 ID |
| `maxConcurrentEvaluations` | `4` | 同时克隆副本的评估会话上限 |
| `evaluateSeconds` | `300` | 评估时长（秒） |
| `recordStart` | `60` | 评估开始时的预热秒数（不计入结果） |
| `evaluationMode` | `["AUTO"]` | 评估模式：AUTO / FORCE_RATE / FORCE_REPLAY |
| `lossRate` | `0.95` | 产出损耗护栏（0-1，默认扣 5%） |
| `intermediateRatio` | `0.25` | 中间产物过滤比例 |
| `ioErrorRatio` | `0.001` | 动态噪声阈值比例 |
| `catalystItems` | `[]` | 催化剂保留清单（量小也保留为输入） |
| `dedupBlocks` | Storage Drawers 压缩抽屉等 | 库存扫描去重方块 ID |
| `energyStabilityRelaxation` | `2.0` | 能量条目稳定性容差放宽倍数 |
| `stressStabilityRelaxation` | `2.0` | 应力条目稳定性容差放宽倍数 |
| `enableFactoryRevert` | `true` | 是否允许启动棒把工厂还原为原机器 |

## 开发指令（OP 权限 2）

所有指令都挂在根命令 `/ccmpor` 下（仅 OP 可用）：

| 指令 | 说明 |
|------|------|
| `/ccmpor room code` | 显示当前房间号（可点击复制） |
| `/ccmpor room enter original <room>` | 传送进入原房间 |
| `/ccmpor room enter eval <room>` | 传送进入 eval_world 副本同坐标位置 |
| `/ccmpor room diff <room>` | 对比原房间与副本的持久化区块差异 |
| `/ccmpor evaluation list` | 列出进行中的评估会话 |
| `/ccmpor evaluation max` | 查看当前并发评估上限 |
| `/ccmpor evaluation max <value>` | 设置并发评估上限（1-16，本次运行生效） |

## 构建与运行

需要 **Java 21**。

```bash
# 构建
./gradlew build

# 运行客户端
./gradlew runClient

# 运行服务端
./gradlew runServer
```

依赖通过 Maven 在线拉取：
- Create / Ponder / Flywheel：`https://maven.createmod.net`
- Registrate：`https://maven.ithundxr.dev/snapshots`
- CompactMachines：CurseForge（`https://cursemaven.com`）

## 参考来源与许可

本模组在开发中参考了以下项目（仅学习其设计思路并在自身结构下重新实现，未直接复制代码）：

- **Create（机械动力）** — 应力系统、传动杆渲染、安山传动箱与创造马达的实现；模型/纹理通过 `create:` 命名空间引用而非复制。
- **CompactMachines** — 压缩房间、机器方块与房间模板机制。
- **CompactMachinesPOR** — 平行房间评估与工厂方块的设计思路。

许可证：
- **代码**：LGPL-3.0
- **资源文件**：本模组的模型仅通过引用复用 Create 命名空间资源，未包含他人纹理 PNG。新增的本模组原创资源默认 ARR（All Rights Reserved）。
