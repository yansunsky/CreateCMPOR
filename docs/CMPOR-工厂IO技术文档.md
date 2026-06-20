# CompactMachinesPOR (CMPOR) 工厂与 IO 方块 技术文档

> 分析对象：CompactMachinesPOR（feat-replay-evaluation 分支），Minecraft 1.21.1，NeoForge 21.1.219
> modid = `compactmachinespor`，主类 `com.compactmachinespor.Cyumocompactmachinespor`
> 前置：CompactMachines core-api 7.0.81（CurseForge 224218）

---

## 一、整体工作流（最重要）

CMPOR 是一个「评估 → 固化」两阶段系统：

1. **评估阶段**：玩家用 `launcher_stick`（启动棒）右键 CompactMachines 的「绑定机器方块」(`BoundCompactMachineBlock`)，
   mixin 把它换成 `EvaluatorBlock`。压缩空间内部，玩家摆放 `input_block` / `output_block`，被激活后充当真实物料进出口；
   CMPOR 记录每秒的物品/流体/能量 IO 速率（默认 300 秒，`EVALUATE_SECONDS`）。
2. **固化阶段**（`Core.finish`）：评估结束，压缩空间被清空，外部 `EvaluatorBlock` 被替换为 `FactoryBlock`。
   **此后 `FactoryBlock` 完全自给自足，不再连接任何压缩空间**——它把录得的 IO 模式固化进自己内部的容器，自行按 pattern 循环吞吐。

### ⚠️ 对集成的关键结论
- `InputBlock`/`OutputBlock`（内部 IO）与 `FactoryBlock`（外部）**不共享底层容器，也无实时内外数据通道**。
- 二者唯一关联是评估期间的 `roomCode` 字符串 + 全局 `Core.MACHINES` 内存表；评估一结束 `Machine` 即销毁。
- **没有「某个面 ↔ 某个内部方块」的逐通道配对**。所有同房间 IO 方块共用同一 roomCode → 同一 Machine。
- `FactoryBlock` 通过 capability 对外暴露 Item/Fluid/Energy handler，**所有面返回同一 handler（side 被忽略）**。

---

## 二、方块/方块实体/物品注册（`Cyumocompactmachinespor.java`）

| 字段 | 注册名 | 类 |
|------|--------|----|
| `INPUT_BLOCK` | `input_block` | `InputBlock` / `InputBlockEntity` |
| `OUTPUT_BLOCK` | `output_block` | `OutputBlock` / `OutputBlockEntity` |
| `FACTORY_BLOCK` | `factory_block` | `FactoryBlock` / `FactoryBlockEntity`（物品 `FactoryBlockItem`） |
| `EVALUATOR_BLOCK` | `evaluator_block` | `EvaluatorBlock` / `EvaluatorBlockEntity` |
| `LAUNCHER_STICK` | `launcher_stick` | 启动棒物品 |

Capability 注册（`registerCapabilities`，监听 `RegisterCapabilitiesEvent`）：
对 `INPUT/OUTPUT/FACTORY_BLOCK_ENTITY` 各注册 `Capabilities.ItemHandler.BLOCK / FluidHandler.BLOCK / EnergyStorage.BLOCK`，
回调 `(be, side) -> be.getXxxHandler()`，**side 被忽略**。`EVALUATOR_BLOCK_ENTITY` 无 capability。

所有上述 BE 的共同基类 `RoomCodeBlockEntity` 持有 `public String roomCode;`，提供 `getRoomCode/setRoomCode`。

---

## 三、工厂方块 `FactoryBlockEntity`（自包含机器）

继承 `RoomCodeBlockEntity`，自己持有全部容器：
```java
public static class Container { public final int capacity; public int amount; }
private final Map<Item, Container>  inputItems, outputItems;     // LinkedHashMap
private final Map<Fluid, Container> inputFluids, outputFluids;
private EnergyStorage inputEnergy, outputEnergy;                 // null=该通道不存在
private boolean lastSuccess;
```

### 对外 capability（三个匿名 handler）
- `getItemHandler() : IItemHandler` — 槽位 `[0..inputN)` 只能 `insertItem`（塞进 inputItems），`[inputN..]` 只能 `extractItem`（从 outputItems 取）。
- `getFluidHandler() : IFluidHandler` — `fill`→inputFluids，`drain`→outputFluids。
- `getEnergyHandler() : IEnergyStorage` — `receiveEnergy`→inputEnergy，`extractEnergy`→outputEnergy。

### 驱动逻辑
```java
static boolean isReady(be)   // 所有 input 满 && 所有 output 空
static void    operate(be)   // 清空 input，填满 output —— 一次"配方"
static void    tick(...)     // 每 20 tick：传统模式 isReady→operate；回放模式按秒 pattern
```
当外部抽走 output 后，extract/drain 内会在 `!lastSuccess && isReady` 时主动再 `operate()`，避免卡死。

### 写入数据的入口（由 `Core.finish` 一次性调用）
```java
public void initTanks(Map<Holder<?>, Double> inputMap, Map<Holder<?>, Double> outputMap)   // 传统模式：稳定速率
public void setRecordPatterns(Map<Item,int[]> inItem, Map<Fluid,int[]> inFluid, int[] inEnergy,
                              Map<Item,int[]> outItem, Map<Fluid,int[]> outFluid, int[] outEnergy)  // 回放模式
```
容量换算：传统 `capacity = floor(rate*20)`；回放 `capacity = max(pattern)*20`。

> **能量通道（`inputEnergy`/`outputEnergy` + `IEnergyStorage` + pattern）是 Create 应力集成最可参考的模板**——
> 应力可视为「第四种资源」，复制能量那一套。

---

## 四、IO 方块基类 `BaseIOBlock` / `BaseIOBlockEntity`

### `BaseIOBlock`（abstract extends BaseEntityBlock）
- **激活状态**：`public static final BooleanProperty ACTIVE = BooleanProperty.create("active");` 默认 `false`。
  无 facing/方向属性——IO 方块不区分朝向。
- `useItemOn(...)`：右键用于**配置白名单**（手持物品 → 登记允许过的 item；手持满桶 → 登记 fluid），不是激活。

### `BaseIOBlockEntity`（abstract extends RoomCodeBlockEntity）
```java
protected List<ResourceLocation> items, fluids;   // 白名单
public abstract IItemHandler   getItemHandler();
public abstract IFluidHandler  getFluidHandler();
public abstract IEnergyStorage getEnergyHandler();

protected boolean isActive() {
    if (getBlockState().getValue(BaseIOBlock.ACTIVE)) return checkAndDeactivate();
    return false;
}
protected boolean check() { return roomCode != null && Core.getMachine(roomCode) != null; } // 评估进行中？
protected void deactivate() { ... setValue(ACTIVE, false) ... }
protected void handle(Holder<?> holder, int count) {   // 上报吞吐量
    if (!checkAndDeactivate()) return;
    Core.setMachineData(roomCode, getDataSetType(), holder, count, Core.getTicks(sl));
    Core.getMachine(roomCode).addTotal(...);
}
```

### 激活机制（核心问答）
- **默认不激活**（ACTIVE=false → isActive() 返回 false → 所有 extract/drain 返回空）。
- **触发激活**：**不是右键、不是红石**，而是评估启动时 `Core.scanRoom()` 末尾**批量**设置：
  ```java
  for (BlockPos pos : machine.IOBlocks)
      if (state.hasProperty(ACTIVE))
          compactWorld.setBlock(pos, state.setValue(ACTIVE, true), UPDATE_ALL);
  ```
  之前 `processBlock()` 把每个 IO 方块加入 `machine.IOBlocks` 并 `setRoomCode(roomCode)`。
- **激活窗口 = 评估窗口**：`isActive()` 每次都 `checkAndDeactivate()`，一旦 `Core.getMachine(roomCode)` 不存在就自动复位 ACTIVE=false。

### Input vs Output
- `InputBlockEntity`：源。激活后 `extractItem`/`drain`/`extractEnergy` 返回请求量并 `handle()` 记为 Input；插入恒拒绝。
- `OutputBlockEntity`：汇。激活后 `insertItem`/`fill`/`receiveEnergy` 吃下并 `handle()` 记为 Output；抽取恒空。

---

## 五、核心数据结构 `Core` / `Machine`

### `core/Core.java`（全局静态）
```java
private static final Map<String, Machine> MACHINES;    // roomCode → Machine
private static final Map<String, UUID>    ROOM2UUID;   // roomCode → 区块票UUID
public  static Machine getMachine(String roomCode);
public  static IRoomBoundaries getRoomBoundaries(ServerLevel level, String roomCode);
public  static void    finish(String roomCode, BlockPos overworldPos);  // 固化 + 解耦
```
- 内外纽带 = `roomCode` 字符串。
- `EvaluatorBlockEntity.trigger()` → `Core.createMachine(serverLevel, roomCode, getBlockPos())`，`getBlockPos()`=外部坐标=`Machine.TargetPos`。
- 内部 IO 方块通过 `Core.getMachine(roomCode)` 反查 Machine，再由 `Machine.TargetPos`（overworld 坐标）知道外部方块位置。

### `core/Machine.java`（一次评估会话）
```java
public final Map<Holder<?>, Data> InputData, OutputData;  // 每资源 int[EVALUATE_SECONDS] 每秒速率
public final List<BlockPos> IOBlocks;      // 房间内被扫到的 IO 方块坐标(压缩维度)
public final String   RoomCode;
public final BlockPos TargetPos;           // 外部世界(overworld)中 Factory/Evaluator 坐标
public List<Data> EnergyData;              // [input, output]
public static final int EVALUATE_SECONDS;  // 默认 300
public enum DataSetType { Input, Output }
public record Data(int[] data) {}
```

### 维度/坐标获取
```java
ServerLevel compactWorld = server.getLevel(CompactDimension.LEVEL_KEY);    // 压缩维度
IRoomBoundaries b = Core.getRoomBoundaries(compactWorld, roomCode);
b.outerBounds();          // AABB（含墙壁外框）
b.innerChunkPositions();  // Stream<ChunkPos>
// 外部坐标 = Machine.TargetPos（overworld）
```

---

## 六、用到的 CompactMachines core-api

| API 成员 | 用途 |
|----------|------|
| `dev.compactmods.machines.api.CompactMachines#room(server, roomCode)` | 取 `Optional<RoomInstance>` |
| `dev.compactmods.machines.api.dimension.CompactDimension#LEVEL_KEY` | 压缩维度 `ResourceKey<Level>` |
| `dev.compactmods.machines.api.room.RoomInstance#boundaries()` | `IRoomBoundaries` |
| `IRoomBoundaries#outerBounds()` / `#innerChunkPositions()` | 房间外框 / 内部区块 |
| `dev.compactmods.machines.api.machine.MachineColor#fromARGB(int)` | 机器颜色 |
| `dev.compactmods.machines.api.component.CMDataComponents.BOUND_ROOM_CODE / MACHINE_COLOR` | 物品组件 |
| `dev.compactmods.machines.server.CompactMachinesServer.CHUNK_TICKET_CONTROLLER#forceChunk(...)` | 强加载房间区块 |
| `dev.compactmods.machines.machine.block.BoundCompactMachineBlock(Entity)` | mixin 目标 / `connectedRoom()` |

依赖声明（build.gradle）：core-api 从 `curse.maven:compact-machines-224218:7621553` 的 jarjar 抽出，`compileOnly`；完整 CM jar 同时 `implementation`。

---

## 七、可复用的 hook 点（供集成模组）

| Hook | 作用 |
|------|------|
| `Core.scanRoom` | 评估时识别 + 激活 IO 方块、登记 roomCode |
| `Core.finish(roomCode, overworldPos)` | 固化工厂数据、内外解耦点 |
| `BaseIOBlockEntity.handle(...)` | IO 记账模板 |
| `FactoryBlockEntity.setRecordPatterns/initTanks` | 写入工厂数据的入口 |
| `FactoryBlockEntity.getItemHandler/getFluidHandler/getEnergyHandler` | 工厂对外 capability |
| `Cyumocompactmachinespor.registerCapabilities` | capability 注册模板 |

> CMPOR 未预留正式扩展 API，所有逻辑集中在 `Core` 静态方法 + 私有 MACHINES 表。
</content>
