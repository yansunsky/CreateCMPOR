# CreateCMPOR

> Create 应力系统 与 CompactMachinesPOR 工厂的兼容性附属模组
> Minecraft 1.21.1 · NeoForge 21.1.219

## 简介

CreateCMPOR 是一个附属模组，把 [Create（机械动力）](https://github.com/Creators-of-Create/Create) 的**应力（Stress）系统**接入 [CompactMachinesPOR](https://www.curseforge.com/) 的**工厂方块（Factory Block）**。它新增两个方块，并提供独立的创造模式物品栏。

### 方块

| 方块 | 外观 | 功能 |
|------|------|------|
| **IO 拓展方块**（`io_extension`） | 复用安山传动箱（`create:andesite_encased_shaft`） | 像普通传动轴一样被动接入 Create 应力网络。**轴向两端**为应力接口面（贴工厂开口面/接传动杆/轴向相连其他拓展方块）；**其余四面**把链上触达的 CMPOR 工厂方块的物品/流体/能量缓存（输入+输出）拓展到此处（指向工厂自身缓存，不复制物品）。 |
| **应力 I/O 方块**（`stress_io`） | 复用创造马达（`create:creative_motor`） | 放置于压缩空间内部，**默认不激活**，空手右键激活。激活后根据本地应力网络盈亏作为应力源/读取器工作。 |

### 配置项

`enableStressOutput`（布尔，默认 `true`）：
- `true`：应力 > 0 时向外提供（输出模式），同时处理 < 0 的输入情况。
- `false`：禁止输出，仅在应力 < 0 时从外部吸取应力，绝不向外提供。

## 设计说明（重要）

CMPOR 是「评估 → 固化」两阶段系统：工厂方块在评估结束后**完全自包含**地按录得的 IO 模式循环吞吐，**与压缩空间不再有实时通道**。因此本模组采用「**应力作为工厂第四种资源**」的思路，集成**在固化后的工厂方块上生效**——应力拓展方块作为外部桥梁读写工厂 IO，与 CMPOR 原有的物品/流体/能量通道同构。

由于 CMPOR 未提供扩展 API、其工厂容器为 private，本模组不修改 CMPOR 源码，集成逻辑全部在附属模组侧完成。内部应力 I/O 方块与外部拓展方块的**完整跨维度配对**为后续迭代目标，当前版本保证两方块各自功能正确、可编译、可渲染、可运行。

## 构建与运行

需要 **Java 21**（如 `C:\Program Files\zulujava\jdk-21`）。

```bash
# 构建
./gradlew build

# 运行客户端测试
./gradlew runClient

# 数据生成（如启用）
./gradlew runData
```

依赖通过 Maven 在线拉取：
- Create / Ponder / Flywheel：`https://maven.createmod.net`
- Registrate：`https://maven.ithundxr.dev/snapshots`
- CompactMachines：CurseForge（`https://cursemaven.com`）
- CompactMachinesPOR：本地 `libs/compactmachinespor.jar`（运行测试用）

## 参考来源与许可

本模组在开发中参考了以下项目（仅学习其设计思路并在自身结构下重新实现，未直接复制代码）：

- **Create（机械动力）** — 应力系统、传动杆渲染、安山传动箱与创造马达的实现。源码分析；模型/纹理通过 `create:` 命名空间引用而非复制。
- **CompactMachinesPOR** — 工厂方块与 IO 方块机制（许可证 LGPL-3.0）。
- **CompactMachines** — CMPOR 的前置。

许可证：
- **代码**：LGPL-3.0
- **资源文件**（模型/纹理等）：本模组的模型仅通过引用复用 Create 命名空间资源，未包含他人纹理 PNG。新增的本模组原创资源默认 ARR（All Rights Reserved）。
</content>
