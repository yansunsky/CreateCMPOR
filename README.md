# CreateCMPOR

> Create（机械动力）× CompactMachines 平行房间评估系统
> Minecraft 1.21.1 · NeoForge 21.1.x · Create 6.0.10-281 · CompactMachines 7.0.81
> 当前版本：**0.4.27**

## 简介

CreateCMPOR 是一个附属模组，把 [Create（机械动力）](https://github.com/Creators-of-Create/Create) 的**应力系统**与 [CompactMachines](https://www.curseforge.com/minecraft/mc-mods/compact-machines) 的**压缩房间**整合成一套「**平行房间评估 → 工厂复刻**」系统：对压缩房间内的整条机器产线做一次自动化评估，把它的输入/输出模式固化成一个可独立运转的**工厂方块**，在压缩空间之外复刻同样的产能。

本模组**基于 [CompactMachinesPOR](https://www.curseforge.com/minecraft/mc-mods/compactmachinespor) 重构而来**（平行房间评估 + 工厂方块），以 Create 附属模组的形式在 Minecraft 1.21.1 上重新实现。

作者：**yansunsky**（LGPL-3.0）

每个方块都附带 **Ponder 思索**（悬停按思索键触发，中英双语）；工厂方块额外演示**三种包壳形态**与完整生命周期。

## 玩法流程

1. 在压缩房间里搭好产线，放置**应力输入/输出方块**（供评估期测量应力）和**输入/输出方块**（作为物品/流体进出口）。
2. 手持**平行评估启动棒**右键房间的 CompactMachines 机器 → 房间冻结，内容克隆到 `eval_world` 副本。
3. 评估期自动激活 IO 方块，按真实流量记录：
   - 预热（不计入结果）→ 基线扫描（S1）→ 采样 → 终态扫描（S2），配合 **S0 三扫描审计**防刷。
4. 判定产出模式：**RATE**（稳定速率连续流）或 **REPLAY**（脉冲录制回放）。
5. 评估方块固化为**工厂方块**：按录得模式持续吞吐，可通过漏斗等物流接入。
6. 手持启动棒右键工厂，可还原为原 CompactMachines 机器。

### 并行空间输入方块（多工厂评估）

在房间里放置**并行空间输入方块**（`parallel_input_block`），与普通输入方块一样用物品右键配置白名单。一次评估会**按白名单物品逐个分支评估**（每个分支只暴露该物品采样），评估完成后在机器位置**垂直向上固化 N 个工厂方块**（N = 白名单物品数）。重叠方块会被破坏掉落——但**不可破坏方块（如基岩）会在评估开始前就被预检测拦截**（生存模式无法移动它们）。组内每个工厂的护目镜 tooltip 都显示**房间号**与**同类工厂数量**，右键任一个成员会**整组还原**（其余成员消失不掉落）。

## 工厂微缩预览（0.4.0+）

固化后的**工厂方块会把整条产线以微缩形态画在自己肚子里**，而且是活的：

- **三种外壳形态**（切换都不消耗材料）：
  - **展示形态**（默认）：3px 底座 + 四角立柱 + 顶部横梁 + 四面玻璃，隔着玻璃能看到里面；
  - 手持 **Create 安山机壳**右键 → 传统机壳外形，六个面都能用扳手开传动轴面接应力；
  - 手持 **Create 边框玻璃**右键 → 撤掉自带罩子，只剩底座，一览无遗；
  - **潜行 + 扳手**逐级退回（罩子 → 机壳 → 展示形态），两者都没有时再潜行扳手才是**拆下工厂**
    （掉落携带完整方块实体数据的物品，可异地重建）。
- **微缩内容**：产线方块按真实方块状态渲染；会转的机械部件（轴、齿轮、装箱齿轮、粉碎轮、磨石、
  变速箱、离合、创造马达、水车、飞轮、转盘、动力轴、装箱风扇、机械泵、机械钻……）**在微缩里真的转**；
  机械轴承、风车轴承这类**旋转型装置**会带着它托起的整块结构一起转；房间里的**生物、掉落物、展示框**
  也会一起出现在微缩里。
- **物品预览**：工厂物品拿在手上、放在物品栏 / GUI / JEI 里，同样显示自带微缩。
- **流量**：快照默认**按需同步**——客户端真要渲染时才向服务端索取一次，之后按版本号命中本地缓存，
  不再随每次方块实体更新下发（`FULL` 模式仍可在配置里切回）。
- **体积护栏**：单件工厂物品的方块实体 NBT 超过 `previewItemMaxKb` 时，掉落瞬间逐级瘦身
  （先丢微缩里的生物/装置段，再丢整份预览），避免撞上客户端读包内 NBT 的 2 MB 硬配额而让对方断线。
- 诊断：服务端 `/ccmpor preview info | entities | bumprev`，客户端 `/ccmporc preview sync`。

## 方块

| 方块 | 说明 |
|------|------|
| **平行评估启动棒**（`launcher_stick`） | 右键房间机器开始评估；右键工厂可还原为原机器。 |
| **工厂方块**（`factory_block`） | 评估结果落点。默认「展示形态」：方块内渲染正在运转的**微缩产线**（见上节）。手持安山机壳右键切成传统机壳外形（六面用扳手按面开关应力接口），手持边框玻璃右键撤掉自带罩子。按 RATE/REPLAY 模式复刻产线，自带输入/输出缓存。 |
| **IO 拓展方块**（`io_extension`） | 像普通传动轴一样接入 Create 应力网络。**轴向两端**为应力接口面（贴工厂开口面/接传动杆/轴向相连其他拓展方块）；**非轴向四面**把链上触达的工厂的物品/流体/能量缓存（输入+输出）拓展到此处（直接指向工厂自身缓存，不复制物品）。若轴向两端同时连到两个工厂缓存，自动破坏自身防止物品复制漏洞。 |
| **并行空间输入方块**（`parallel_input_block`） | 与输入方块类似，但白名单里每个物品成为一个评估分支，每个分支固化一个工厂（垂直堆叠）。 |
| **应力输入方块**（`stress_input`） | 房间内，评估期激活作为应力源，驱动内部机器以测得真实应力消耗。右键拖拽注记框可调转速/转向（-256~+256 RPM，默认 16）。 |
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
| `evaluateSeconds` | `60` | 评估时长（秒） |
| `recordStart` | `60` | 评估开始时的预热秒数（不计入结果） |
| `evaluationMode` | `["AUTO"]` | 评估模式：AUTO / FORCE_RATE / FORCE_REPLAY |
| `lossRate` | `0.95` | 产出损耗护栏（0-1，默认扣 5%） |
| `intermediateRatio` | `0.25` | 中间产物过滤比例 |
| `ioErrorRatio` | `0.001` | 动态噪声阈值比例 |
| `catalystItems` | `[]` | 催化剂保留清单（量小也保留为输入） |
| `dedupBlocks` | Storage Drawers 压缩抽屉等 | 库存扫描去重方块 ID |
| `energyStabilityRelaxation` | `2.0` | 能量条目稳定性容差放宽倍数 |
| `stressStabilityRelaxation` | `2.0` | 应力条目稳定性容差放宽倍数 |
| `continueOnSourceReloaded` | `false` | 源房间区块被卸载/重载后是否继续评估 |
| `unloadStuckPendingTicks` | `0` | 副本区块卡在卸载队列多少 tick 后放行（0=严格等待） |
| `enableEvaluationObservation` | `true` | 是否启用评估维度的观察者监管（越界自动退出） |
| `observationPermissionLevel` | `0` | 可自由进入评估副本观察的权限等级 |
| `enableFactoryRevert` | `true` | 是否允许启动棒把工厂还原为原机器 |

### 微缩预览（0.4.0+，单位 KB 的都是 NBT 体积）

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `enableFactoryPreview` | `true` | 是否采集并在方块内渲染微缩预览 |
| `enableFactoryItemPreview` | `true` | 手持/物品栏/GUI 是否显示微缩 |
| `previewMaxVolume` | `8192` | 允许采集的房间体积上限（方块数） |
| `factoryPreviewAnimationSeconds` | `4` | 旋转部件一圈的动画周期（秒，0=静止） |
| `previewMaxEntities` / `previewMaxContraptions` | `48` / `8` | 快照里普通实体 / 装置实体的条数上限 |
| `previewEntityMaxKb` / `previewContraptionMaxKb` | `8` / `192` | 单条实体 / 装置 NBT 预算（逐键裁剪，不整只丢弃） |
| `previewEntityTableMaxKb` | `1024` | 整张实体表总体积上限 |
| `previewItemMaxKb` | `1024` | 物品侧体积护栏（超限掉落时逐级瘦身） |
| `previewSyncMode` | `ON_DEMAND` | 快照下发方式：`FULL` 随方块实体包发 / `ON_DEMAND` 客户端索取 |
| `previewRequestMode` | `ON_DEMAND` | 客户端是否主动索取（服务端不支持时一次都不发） |
| `previewSyncMaxKb` | `1024` | 单个同步响应包体积上限 |
| `previewCacheMaxKb` | `16384` | 客户端快照缓存软上限（按 LRU 淘汰） |

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
| `/ccmpor preview info` | 查看附近工厂的预览状态（体积、版本号、实体数） |
| `/ccmpor preview entities` | 列出快照里每只实体的体积与前几大 NBT 键（排查裁剪用） |
| `/ccmpor preview bumprev` | 把最近工厂的快照版本顶一格并强制走一遍按需同步链路（调试） |
| `/ccmporc preview sync` | **客户端**：查看本地快照缓存条数/占用/命中与请求状态 |

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

本模组**基于 [CompactMachinesPOR](https://www.curseforge.com/minecraft/mc-mods/compactmachinespor) 重构而来**，并参考了以下项目（仅学习其设计思路并在自身结构下重新实现，未直接复制代码）：

- **CompactMachinesPOR** — 前身模组；平行房间评估与工厂方块机制均由此重构。
- **Create（机械动力）** — 应力系统、传动杆渲染、安山传动箱与创造马达的实现；模型/纹理通过 `create:` 命名空间引用而非复制。
- **CompactMachines** — 压缩房间、机器方块与房间模板机制。

许可证：
- **代码**：LGPL-3.0
- **资源文件**：本模组的模型仅通过引用复用 Create 命名空间资源，未包含他人纹理 PNG。新增的本模组原创资源默认 ARR（All Rights Reserved）。
