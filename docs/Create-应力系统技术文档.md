# Create 应力系统 / 传动杆渲染 技术文档

> 分析对象：Create 6.0.11，Minecraft 1.21.1，NeoForge 21.1.219
> 源码包：`Create-mc1.21.1-dev.zip` → `com.simibubi.create`

---

## 一、核心概念

| 概念 | 含义 | 单位 |
|------|------|------|
| **Speed（转速）** | 带符号 `float`，正负表示旋转方向。 | RPM（转/分钟） |
| **Stress（应力）** | 网络中机器**消耗**的扭矩。 | SU (Stress Unit) |
| **Capacity（容量）** | 网络中发电机**提供**的扭矩上限。 | SU |
| **过载（Overstressed）** | 当 `currentStress > currentCapacity` 时网络过载，所有机器停转（`getSpeed()` 返回 0）。 | — |

**关键换算**：`BlockStressValues` 中登记的 impact / capacity 是「每 1 RPM 的基准值」。
实际 SU = 基准值 × |speed|。例如创造马达 capacity 基准 = 16384，转速 256 RPM 时实际容量 = 16384 × 256 = 4,194,304 SU。

应力开关：`IRotate.StressImpact.isEnabled()` = `!AllConfigs.server().kinetics.disableStress.get()`，禁用时永不过载。

---

## 二、应力网络 `KineticNetwork`

包路径：`content/kinetics/KineticNetwork.java`（注意不在 `base/` 子包）。

### 公开字段
```java
public Long id;
public boolean initialized;
public Map<KineticBlockEntity, Float> sources;   // 应力源 → 每-RPM容量基准
public Map<KineticBlockEntity, Float> members;   // 全体成员 → 每-RPM应力基准
```

### 私有聚合字段（无 public getter）
```java
private float currentCapacity;   // 网络总容量(实际SU)
private float currentStress;     // 网络总应力(实际SU)
private float unloadedCapacity;
private float unloadedStress;
private int   unloadedMembers;
```

### 关键方法
```java
public void  add(KineticBlockEntity be)
public void  remove(KineticBlockEntity be)
public void  addSilently(KineticBlockEntity be, float lastCapacity, float lastStress)
public void  updateCapacityFor(KineticBlockEntity be, float capacity)   // 更新某源容量
public void  updateStressFor(KineticBlockEntity be, float stress)       // 更新某成员应力
public void  updateStress() / updateCapacity() / updateNetwork()
public void  sync()                       // 把 current 值推送给所有成员 updateFromNetwork
public float calculateCapacity()          // Σsources + unloadedCapacity
public float calculateStress()            // Σmembers  + unloadedStress
public int   getSize()
public float getActualCapacityOf(KineticBlockEntity be)
public float getActualStressOf(KineticBlockEntity be)
```

> ⚠️ **网络本身不暴露 `getStressCapacity`/`getSpeed` 等 getter**，`currentCapacity`/`currentStress` 是 private。
> 外部读取网络状态**必须通过某个成员 `KineticBlockEntity`**（见下）。

---

## 三、成员方块实体 `KineticBlockEntity`

包路径：`content/kinetics/base/KineticBlockEntity.java`，继承 `SmartBlockEntity`。

### 核心字段
```java
public  @Nullable Long     network;   // 所属网络 id，null=无网络
public  @Nullable BlockPos source;    // 旋转来源坐标
protected float speed;                 // 当前转速(RPM, 带符号)
protected float capacity;              // 从网络同步来的【网络总容量】
protected float stress;                // 从网络同步来的【网络总应力】
protected boolean overStressed;
protected float lastStressApplied;     // 上次贡献的每-RPM应力基准
protected float lastCapacityProvided;  // 上次提供的每-RPM容量基准
```

> 成员上的 `capacity`/`stress` 是**整个网络**的总量（由 `network.sync()` → `updateFromNetwork()` 写入），不是单块的值。这正是读取网络状态的入口。

### 读取网络状态（外部桥梁的正确做法）
```java
public float   getSpeed()            // 过载/冻结返回 0，否则 = getTheoreticalSpeed()
public float   getTheoreticalSpeed() // 返回原始 speed
public boolean isOverStressed()      // = overStressed
// capacity - stress = 网络应力余量（字段为 protected，需子类访问）
```
官方范例 `StressGaugeBlockEntity` 定义 `getNetworkStress()`/`getNetworkCapacity()` 暴露这两个 protected 字段。

### 加入/离开网络
```java
public void           setNetwork(@Nullable Long networkIn)
public KineticNetwork getOrCreateNetwork()   // = Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(this)
public boolean        hasNetwork()
public void           setSource(BlockPos source)
public void           removeSource()
public void           attachKinetics()       // RotationPropagator.handleAdded
public void           detachKinetics()        // RotationPropagator.handleRemoved
```

### 应力/容量计算
```java
protected Block getStressConfigKey()             // 默认 getBlockState().getBlock()
public float    calculateStressApplied()         // = BlockStressValues.getImpact(key)
public float    calculateAddedStressCapacity()   // = BlockStressValues.getCapacity(key)
```

### 连接性（哪些面能接轴）
连接性由**方块**实现的 `IRotate` 接口 + `RotationPropagator` 决定，不在 BE 上。可重写钩子：
```java
public float propagateRotationTo(KineticBlockEntity target, BlockState from, BlockState to,
                                 BlockPos diff, boolean viaAxes, boolean viaCogs)   // 默认0
public boolean isCustomConnection(KineticBlockEntity other, BlockState s, BlockState os)  // 默认false
public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> n)
```

---

## 四、发电机基类 `GeneratingKineticBlockEntity`（应力源）

包路径：`content/kinetics/base/GeneratingKineticBlockEntity.java`。**做应力源就继承它**。

```java
public void updateGeneratedRotation()                       // 核心：重算发电速度并推到网络
protected void notifyStressCapacityChange(float capacity)   // = getOrCreateNetwork().updateCapacityFor(this, capacity)
public void applyNewSpeed(float prevSpeed, float speed)     // 处理 0↔转动、建/夺网络
public Long createNetworkId()                                // = worldPosition.asLong()
```

`updateGeneratedRotation()` 逻辑：
```java
float speed = getGeneratedSpeed();
float prev  = this.speed;
if (prev != speed) applyNewSpeed(prev, speed);
if (hasNetwork() && speed != 0) {
    KineticNetwork network = getOrCreateNetwork();
    notifyStressCapacityChange(calculateAddedStressCapacity());  // 推送容量
    network.updateStressFor(this, calculateStressApplied());      // 推送应力
    network.updateStress();
}
onSpeedChanged(prev);
sendData();
```

子类必须重写 `getGeneratedSpeed()` 返回非 0 带符号 RPM，并在 tick/初始化/参数变更时调用 `updateGeneratedRotation()`。

---

## 五、旋转传播 `RotationPropagator`（纯静态）

包路径：`content/kinetics/RotationPropagator.java`。

`getRotationSpeedModifier(from, to)` 返回 from→to 转速倍率（含符号，负=反向）：
1. `from.propagateRotationTo(...)` 非 0 优先（自定义连接）。
2. 轴连接：双方 `hasShaftTowards` 且对齐 → 普通轴倍率 1。
3. 小齿轮↔小齿轮：相邻垂直 → -1。
4. 大齿轮间 / 大小齿轮：±1 / -2 / -0.5。

拓扑维护：
```java
public static void handleAdded(Level w, BlockPos pos, KineticBlockEntity addedTE)
public static void handleRemoved(Level w, BlockPos pos, KineticBlockEntity removedBE)
```
超速或方向冲突 → `destroyBlock`（炸方块）。

---

## 六、`IRotate` 接口

包路径：`content/kinetics/base/IRotate.java`，继承 `IWrenchable`。**参与旋转网络的方块（不是 BE）必须实现它**。
```java
boolean hasShaftTowards(LevelReader w, BlockPos pos, BlockState state, Direction face);
Axis    getRotationAxis(BlockState state);
default SpeedLevel getMinimumRequiredSpeedLevel() { return SpeedLevel.NONE; }
enum SpeedLevel { NONE, SLOW, MEDIUM, FAST; }
enum StressImpact { LOW, MEDIUM, HIGH, OVERSTRESSED; static boolean isEnabled(); }
```

---

## 七、应力值配置 `BlockStressValues`

包路径：`api/stress/BlockStressValues.java`。
```java
public static final SimpleRegistry<Block, DoubleSupplier> IMPACTS;     // 每-RPM应力基准
public static final SimpleRegistry<Block, DoubleSupplier> CAPACITIES;  // 每-RPM容量基准
public static final SimpleRegistry<Block, GeneratedRpm>   RPM;         // tooltip用
public static double getImpact(Block block);     // 未登记 → 0
public static double getCapacity(Block block);   // 未登记 → 0
```

> ⚠️ Create 自身用 `CStress.setImpact/setCapacity`，但其内部 `assertFromCreate(builder)` **对非 Create 方块抛异常**。
> **外部附属模组必须直接调注册表**：
> ```java
> BlockStressValues.IMPACTS.register(myBlock, () -> 4.0);      // 应力消耗基准
> BlockStressValues.CAPACITIES.register(myBlock, () -> 16384.0); // 容量基准(做源)
> ```

---

## 八、创造马达 `CreativeMotor`（应力源参考实现）

包路径：`content/kinetics/motor/CreativeMotorBlockEntity.java`，继承 `GeneratingKineticBlockEntity`。
```java
public static final int DEFAULT_SPEED = 16;
public static final int MAX_SPEED = 256;
public ScrollValueBehaviour generatedSpeed;

@Override public float getGeneratedSpeed() {
    if (!AllBlocks.CREATIVE_MOTOR.has(getBlockState())) return 0;
    return convertToDirection(generatedSpeed.getValue(),
            getBlockState().getValue(CreativeMotorBlock.FACING));
}
@Override public void initialize() {
    super.initialize();
    if (!hasSource() || getGeneratedSpeed() > getTheoreticalSpeed())
        updateGeneratedRotation();
}
```
应力值在 `AllBlocks.java` 注册：`.transform(CStress.setCapacity(16384.0))`。
「无限供应力」靠巨大 capacity 基准（16384 × |speed|）+ 可调速度。

---

## 九、安山传动箱（Andesite Encased Shaft）渲染

### 方块定义
- `content/kinetics/simpleRelays/encased/EncasedShaftBlock.java`
  `class EncasedShaftBlock extends AbstractEncasedShaftBlock implements IBE<KineticBlockEntity>, ...`
  构造 `EncasedShaftBlock(Properties, Supplier<Block> casing)`。
- `content/kinetics/base/AbstractEncasedShaftBlock.java extends RotatedPillarKineticBlock`
  - `hasShaftTowards(...)` → `face.getAxis() == state.getValue(AXIS)`（沿轴两端可接轴）
  - `getRotationAxis(state)` → `state.getValue(AXIS)`
- `content/kinetics/base/RotatedPillarKineticBlock.java`
  `public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;` 默认 Y。

### 方块实体
直接复用 **`KineticBlockEntity`**（不需子类）。

### 渲染（两套并行）
- **BER 回退**：`content/kinetics/base/ShaftRenderer<T> extends KineticBlockEntityRenderer<T>`
  重写 `getRenderedBlockState(be) → shaft(getRotationAxisOf(be))`，把内部画成旋转的 `SHAFT` 模型；外壳走静态方块模型。
- **Flywheel visual**：`SingleAxisRotatingVisual::shaft`（`base/SingleAxisRotatingVisual.java`）使用 `Models.partial(AllPartialModels.SHAFT)`。

### 注册（`AllBlockEntityTypes.ENCASED_SHAFT`，参考）
```java
REGISTRATE.blockEntity("encased_shaft", KineticBlockEntity::new)
    .visual(() -> SingleAxisRotatingVisual::shaft, false)   // Flywheel
    .validBlocks(ANDESITE_ENCASED_SHAFT, ...)
    .renderer(() -> ShaftRenderer::new)                      // BER 回退
    .register();
```

### 资源文件
| 类型 | 路径 / 内容 |
|------|------|
| Blockstate | `assets/create/blockstates/andesite_encased_shaft.json` — 三轴变体共用模型 `create:block/encased_shaft/block_andesite` + x/y 旋转 + uvlock |
| 方块模型 | `assets/create/models/block/encased_shaft/block_andesite.json`：`parent=create:block/encased_shaft/block`，`textures.casing=create:block/andesite_casing`，`textures.opening=create:block/gearbox` |
| 物品模型 | `assets/create/models/block/encased_shaft/item_andesite.json`（含静态轴几何） |
| 外壳纹理 | `create:block/andesite_casing`（`andesite_casing.png` 及 CT 变体） |
| 开口纹理 | `create:block/gearbox` |
| 轴纹理 | `create:block/axis` + `create:block/axis_top`（来自 `create:block/shaft` 模型） |
| Lang | `block.create.andesite_encased_shaft` = "Andesite Encased Shaft" |

### 复用结论（附属模组）
- 方块继承 `EncasedShaftBlock`（构造传入外壳供应器），或直接 `RotatedPillarKineticBlock`。
- BE 复用 `KineticBlockEntity`，或为加应力源逻辑而继承 `GeneratingKineticBlockEntity`。
- 新建自己的 BE 类型并 `.renderer(() -> ShaftRenderer::new)` + `.visual(() -> SingleAxisRotatingVisual::shaft, false)`。
- 模型直接 `parent`/引用 `create:` 命名空间，无需复制 PNG。
</content>
</invoke>
