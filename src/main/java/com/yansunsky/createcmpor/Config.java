package com.yansunsky.createcmpor;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * 模组配置（COMMON）。
 * Mod configuration (COMMON).
 */
public class Config {

    public static final ModConfigSpec SPEC;

    /**
     * 是否允许应力 IO 方块向外提供应力。
     * Whether stress I/O blocks are allowed to provide stress outward.
     * <ul>
     *     <li>{@code true}（默认）：应力大于 0 时向外提供（输出模式），同时也处理小于 0 的输入情况。</li>
     *     <li>{@code false}：禁止输出，仅在应力小于 0 时从外部吸取应力，绝不向外提供。</li>
     * </ul>
     */
    public static final ModConfigSpec.BooleanValue ENABLE_STRESS_OUTPUT;

    /**
     * 开发期：是否允许空手右键手动激活/停用应力 IO 方块。
     * Dev-only: allow empty-hand right-click to manually toggle stress I/O blocks.
     * <ul>
     *     <li>{@code true}（开发期）：保留右键手动切换，便于测试人员激活。</li>
     *     <li>{@code false}（默认，生产）：仅由本模组评估流程自动激活，右键无效。</li>
     * </ul>
     */
    public static final ModConfigSpec.BooleanValue DEV_MANUAL_ACTIVATION;

    /**
     * 应力输出损耗系数（0~1）。应力拓展方块对外输出应力时的损耗：
     * 实际输出应力 = 工厂可提供应力 ×(1 - stressLossFactor)。
     * Stress output loss factor (0~1). Loss when the stress extension block outputs stress:
     * actual output = factory available stress ×(1 - stressLossFactor).
     */
    public static final ModConfigSpec.DoubleValue STRESS_LOSS_FACTOR;
    public static final ModConfigSpec.BooleanValue ENABLE_INVENTORY_AUDIT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SUSPICIOUS_MODS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SUSPICIOUS_BLOCKS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SUSPICIOUS_ITEMS;
    public static final ModConfigSpec.IntValue MAX_CONCURRENT_EVALUATIONS;
    public static final ModConfigSpec.IntValue EVALUATE_SECONDS;
    public static final ModConfigSpec.IntValue RECORD_START;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EVALUATION_MODE;
    public static final ModConfigSpec.DoubleValue LOSS_RATE;
    public static final ModConfigSpec.DoubleValue INTERMEDIATE_RATIO;
    public static final ModConfigSpec.DoubleValue IO_ERROR_RATIO;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CATALYST_ITEMS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DEDUP_BLOCKS;
    public static final ModConfigSpec.DoubleValue ENERGY_STABILITY_RELAXATION;
    public static final ModConfigSpec.DoubleValue STRESS_STABILITY_RELAXATION;
    public static final ModConfigSpec.BooleanValue ENABLE_FACTORY_REVERT;

    /**
     * 源房间冻结成功后其区块又被其他机制重新加载时，是否跳过 idle 复检直接继续评估。
     * Whether to skip the idle re-check and continue when the frozen source room's chunks get re-loaded.
     * <ul>
     * <li>{@code false}（默认，严格）：卸载后源区块再次加载即安全取消（原设计，零修改快照保证）。</li>
     * <li>{@code true}（有风险）：卸载确认成功后，即使源区块被其他模组/机制复载也继续克隆评估——
     * 快照可能不再是严格冻结态（源若在评估期间持续 tick 可能被修改），仅当环境无法让房间保持卸载时用于解除阻塞。</li>
     * </ul>
     */
    public static final ModConfigSpec.BooleanValue CONTINUE_ON_SOURCE_RELOADED;

    /**
     * 源房间区块卡在 vanilla 卸载队列（{@code ChunkMap.pendingUnloads}）时的容忍 tick 数。
     * Ticks to tolerate a source chunk stuck in vanilla's pending-unload queue.
     * <ul>
     * <li>{@code 0}（默认）：严格——卡住即视为未卸载，200 tick 后冻结失败回滚（原行为）。</li>
     * <li>{@code >0}：连续 N tick 观察到"仅 pendingUnloads 卡住、其余子条件全空"时，
     * 视为已卸载并继续冻结。用于规避 vanilla {@code scheduleUnload} 无限重试导致的永久卡死
     * （实测：区块内容早已卸载、无任何票据，却永远停在 pendingUnloads → 房间永久无法评估）。</li>
     * </ul>
     */
    public static final ModConfigSpec.IntValue UNLOAD_STUCK_PENDING_TICKS;

    /**
     * 是否允许玩家以观察者模式进入评估副本查看产线。
     * Whether players may enter a running evaluation replica as a spectator.
     * <ul>
     * <li>{@code true}（默认）：手持 {@code compactmachines:personal_shrinking_device}
     * 右键评估方块即可进入；任何被传送指令送入评估维度的玩家也会被自动转为观察者。</li>
     * <li>{@code false}：完全关闭观察功能（评估维度仍由守卫把非 OP 玩家送回主世界）。</li>
     * </ul>
     * 观察者不产生区块票据、也不参与刷怪（vanilla 语义），因此不会影响评估结果。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_EVALUATION_OBSERVATION;

    /**
     * 允许观察评估副本的权限等级。
     * Permission level required to observe an evaluation replica.
     * <ul>
     * <li>{@code 0}（默认）：所有玩家都可观察（供玩家自查产线问题）。</li>
     * <li>{@code 2}：仅管理员（OP）可观察。</li>
     * </ul>
     */
    public static final ModConfigSpec.IntValue OBSERVATION_PERMISSION_LEVEL;

    /**
     * 是否在固化时采集「房间产线微缩预览」快照。
     * Whether to capture the miniature factory preview snapshot when solidifying.
     * <ul>
     * <li>{@code true}（默认）：固化那一刻从评估副本采一份方块快照存进工厂方块，客户端据此渲染微缩产线。</li>
     * <li>{@code false}：完全不采集（工厂方块外观与旧版一致）。</li>
     * </ul>
     * 采集只在固化瞬间发生一次；采集失败只影响预览，不影响固化与评估结果。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_FACTORY_PREVIEW;

    /**
     * 手持/物品栏里的工厂物品是否显示它自己携带的微缩预览（0.4.2）。
     * Whether the held / inventory factory item renders its own miniature preview (0.4.2).
     * <ul>
     * <li>{@code true}（默认）：物品显示自带预览（与方块侧同一套烘焙/绘制管线）；</li>
     * <li>{@code false}：物品只画机壳（与 0.4.1 外观一致），也不烘焙、不占物品侧缓存。</li>
     * </ul>
     * 纯客户端开关：改这一项不需要服务端同步，不影响已采集的快照数据。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_FACTORY_ITEM_PREVIEW;

    /**
     * 微缩预览的网格体积上限（格）。
     * Maximum preview grid volume, in cells.
     * <ul>
     * <li>超过该体积的房间会先按整数步长做盒式降采样，再进入快照；</li>
     * <li>体积上限直接决定工厂 BE 的 NBT 增量与同步包大小（每格 1 字节 + 调色板文本）。</li>
     * </ul>
     */
    public static final ModConfigSpec.IntValue PREVIEW_MAX_VOLUME;

    /**
     * 微缩预览动画的循环时长（秒）。0 = 静态预览。
     * Loop duration of the miniature preview animation, in seconds; 0 = static preview.
     * <ul>
     * <li>{@code 0}：<b>完全不采集转速</b>，快照里不写速度表 → 客户端按"数据决定行为"走静态路径
     *     （服务器与客户端配置不一致也不会出错，因为客户端从不读这个值）；</li>
     * <li>{@code >0}：采集每格转速并让"会转的部件"动起来，该值同时是<b>速度整表缩放的目标周期</b>
     *     （目标最大转速 = {@code 60 / 秒数} RPM，按 {@code k = 目标 / 全表最大转速} 统一缩放，保留啮合比例）。</li>
     * <li>带宽：速度表为稀疏编码（varint 格索引 + float，约 5 字节/可动格），
     *     典型 50~300 个可动格 → 约 0.3~2.5 KB/工厂；{@code 0} 时这部分体积为 0。</li>
     * </ul>
     */
    public static final ModConfigSpec.IntValue PREVIEW_ANIMATION_SECONDS;

    /**
     * 微缩预览里的实体条数上限（v4 实体表）。0 = 不采集实体。
     * Maximum number of entities kept in a miniature preview snapshot (v4 entity table); 0 = capture none.
     * <ul>
     * <li>实体表是随工厂 BE 的 NBT 一起同步的，所以必须有上限：超限时按"到取景盒中心的距离"
     *     <b>确定性截断</b>（保留最显眼的那些，且同一房间每次结果一致，避免快照指纹抖动导致反复重烘）；</li>
     * <li>单条实体"裁剪后 &gt; 8 KB"会先<b>按体积从大到小逐键丢弃</b>（外观键有保护名单），
     *     只有超过 32 KB 硬上限才整只丢弃——0.4.8 的"超 3 KB 就丢"把一屋子牛全丢光了（实机 0.4.10 修正）；</li>
     * <li>玩家与机械动力装置（contraption）不在采集范围内（前者客户端造不出来，后者需要单独重建）；</li>
     * <li>单条实体"NBT 裁剪后 &gt; 3 KB"会被整只丢弃（防一条大数据实体撑爆 BE NBT）。</li>
     * </ul>
     */
    public static final ModConfigSpec.IntValue PREVIEW_MAX_ENTITIES;

    /**
     * 微缩预览里的机械动力装置（contraption：轴承/活塞/龙门/矿车装置/列车车厢…）条数上限。
     * Maximum number of Create contraptions kept in a miniature preview snapshot.
     * <ul>
     * <li>装置单独限流的原因：它的方块结构整个存在实体 NBT 里（{@code Contraption} 复合，
     *     每方块 ≥27 字节 + 每个调色板状态 40~60 字节），一个房间挂 20 个装置会让快照膨胀到几十 KB；</li>
     * <li>单条装置 NBT"裁剪后 &gt; 64 KB"才整只丢弃；超出 64 KB 预算时按体积从大到小丢<b>其它</b>键，
     *     {@code Contraption} 复合本身永不裁剪（裁了就没得画）；</li>
     * <li>超限同样按"到取景盒中心的距离"确定性截断。</li>
     * </ul>
     */
    public static final ModConfigSpec.IntValue PREVIEW_MAX_CONTRAPTIONS;

    /**
     * 微缩预览里<b>单条普通实体</b>的 NBT 预算（<b>KB</b>，1 KB = 1024 字节）。超出即"逐键裁剪"到预算内。
     * NBT budget per plain entity inside a miniature preview snapshot (in <b>KB</b>); oversized entries are key-trimmed.
     * <ul>
     * <li>裁剪只丢<b>大于 {@link #PREVIEW_TRIM_MIN_KEY_KB}</b> 的键——小于它的键一律保留，
     *     因为"结构性小键"（如装置里的 {@code Axis}）丢了会直接改变语义，而省下的字节可以忽略；</li>
     * <li><b>硬上限 = 该值 × 4</b>：逐键裁剪后仍超过它的实体才<b>整只丢弃</b>
     *     （防的是"某个键异常巨大、裁剪也救不回来"的极端存档）；</li>
     * <li>默认 8（KB）：普通实体通常几百字节，8 KB 足够容纳模组挂的额外数据。</li>
     * </ul>
     */
    public static final ModConfigSpec.IntValue PREVIEW_ENTITY_MAX_KB;

    /**
     * 微缩预览里<b>单条装置（contraption）</b>的 NBT 预算（<b>KB</b>）。规则同上，但量级完全不同。
     * NBT budget per Create contraption inside a miniature preview snapshot (in <b>KB</b>).
     * <ul>
     * <li>装置的方块结构整个存在实体 NBT 的 {@code Contraption} 复合里：每方块 ≥27 字节 +
     *     每个调色板状态 40~60 字节 ⇒ 十来个方块 1~3 KB、上百方块可到 <b>100 KB 以上</b>
     *     （实测某玩家的轴承装置 116 KB）；</li>
     * <li>{@code Contraption} 复合<b>永不裁剪</b>（裁了就没得画），所以这个预算实际上是
     *     "其它键的裁剪门槛"，低于装置本身体积时会白裁一轮（0.4.15 的教训）；</li>
     * <li>该值同时决定工厂方块实体 NBT / 客户端同步包的增量，请按你的产线规模调整；
     *     默认 192（KB）、硬上限 = 该值 × 4。</li>
     * </ul>
     */
    public static final ModConfigSpec.IntValue PREVIEW_CONTRAPTION_MAX_KB;

    /**
     * 逐键裁剪的"<b>小键保护</b>"阈值（<b>KB</b>）：小于该体积的键一律不裁。
     * Keys smaller than this are never trimmed (structural small keys carry semantics, not bytes).
     * <ul>
     * <li>0 = 关闭保护（回到"从最大的键开始丢到装得下为止"的旧行为，<b>不推荐</b>：
     *     实测会把装置的 {@code Axis}(38 B) 丢掉 ⇒ Create 静默不再旋转该装置）；</li>
     * <li>默认 1（KB）。</li>
     * </ul>
     */
    public static final ModConfigSpec.IntValue PREVIEW_TRIM_MIN_KEY_KB;

    /**
     * 快照日志里"单条实体过大"的提醒阈值（<b>KB</b>）：超过就打一条日志，但<b>不丢弃</b>。
     * Log a warning when a single captured entity exceeds this size (it is still kept).
     * <ul>
     * <li>用来提前发现"快照会明显变大、客户端同步变重"的情况；</li>
     * <li>默认 48（KB）。</li>
     * </ul>
     */
    public static final ModConfigSpec.IntValue PREVIEW_BIG_ENTITY_WARN_KB;

    /**
     * 快照里<b>整张实体表的总体积上限</b>（<b>KB</b>）。超过就按"到取景盒中心的距离"丢弃剩余条目。
     * Total budget for the whole entity table inside one snapshot (in <b>KB</b>); the rest is dropped by distance.
     * <ul>
     * <li><b>为什么必须有这条</b>：实体表是随工厂方块实体的 {@code getUpdateTag()} 一起同步给客户端的，
     *     而客户端读 NBT 有硬配额 {@code FriendlyByteBuf.DEFAULT_NBT_QUOTA = 2097152}（2 MB）
     *     —— 越过它就不是"显示异常"，而是<b>区块数据包解析失败 / 断线</b>。
     *     单条上限可以配得很大（例如 8 个装置各 1 MB），所以必须再有一道"总量闸门"。</li>
     * <li>默认 1024（1 MB）：足够放一台几百 KB 的大装置 + 几十只普通实体，仍留一半余量给方块网格。</li>
     * </ul>
     */
    public static final ModConfigSpec.IntValue PREVIEW_ENTITY_TABLE_MAX_KB;

    /** 微缩预览快照的同步模式。 */
    public enum PreviewSyncMode {
        /** 快照随方块实体 NBT 一起发（0.4.19 及以前的行为）。 */
        FULL,
        /** 只在客户端真正要渲染这个工厂时才按需索取（省流量，见 docs/工作日志.md 0.4.20 起）。 */
        ON_DEMAND
    }

    /**
     * <b>服务端</b>语义：快照是否随方块实体 NBT 一起发给客户端。
     * Server-side: whether the snapshot rides the block-entity update tag.
     * <ul>
     * <li>{@code ON_DEMAND}（<b>0.4.25 起默认</b>，实机验收通过后翻的默认值）：tag 里只留
     *     {@code preview_rev}/{@code has_preview} 两个轻量键，数据由客户端按需索取；</li>
     *     <li>{@code FULL}：与 0.4.19 行为逐位一致（快照随包发）。旧客户端连上来时服务端会自动回退到它；</li>
     * <li>{@code ON_DEMAND}：tag 里只留 {@code preview_rev}/{@code has_preview} 两个轻量键，
     *     数据由客户端按需索取（C2S 请求 / S2C 响应，带限流与体积闸门）。
     *     <b>注意</b>：装旧版本本模组的客户端在 ON_DEMAND 下会静默看不到微缩（不崩、不断线），
     *     服务端登录时会用 {@code NetworkRegistry.hasChannel} 检测并自动整体回退到 FULL。</li>
     * </ul>
     */
    public static final ModConfigSpec.EnumValue<PreviewSyncMode> PREVIEW_SYNC_MODE;

    /**
     * <b>客户端</b>语义：渲染工厂时是否主动索取快照。
     * Client-side: whether the client asks for the snapshot when it is about to render a factory.
     * <ul>
     * <li>默认 {@code ON_DEMAND}：本地缓存命中就不发请求；服务端若是旧版本/未开启按需同步，
     *     通道不存在（{@code NetworkRegistry.hasChannel == false}）⇒ 一次都不发；</li>
     * <li>{@code FULL}：从不主动请求，完全等服务端推。</li>
     * </ul>
     */
    public static final ModConfigSpec.EnumValue<PreviewSyncMode> PREVIEW_REQUEST_MODE;

    /** 客户端主动请求的生效半径（格）。比渲染 LOD（64）小，天然滞回，避免边缘反复请求。 */
    public static final ModConfigSpec.IntValue PREVIEW_REQUEST_RADIUS;

    /** 同一工厂两次请求之间的最小间隔（tick）。 */
    public static final ModConfigSpec.IntValue PREVIEW_REQUEST_COOLDOWN_TICKS;

    /** 同一工厂最多尝试几次（含指数退避），超过就本会话放弃。 */
    public static final ModConfigSpec.IntValue PREVIEW_REQUEST_MAX_ATTEMPTS;

    /** 每 tick 最多发起几个请求（一次性走进一堆工厂时的全局闸门）。 */
    public static final ModConfigSpec.IntValue PREVIEW_REQUEST_PER_TICK;

    /** 单个响应包的体积上限（KB）；超过则服务端回 {@code TOO_BIG} 不发。依据：客户端 NBT 读配额 2 MB。 */
    public static final ModConfigSpec.IntValue PREVIEW_SYNC_MAX_KB;

    /** 服务端愿意响应请求的最大距离（格）；比请求半径宽，防远程探测。 */
    public static final ModConfigSpec.IntValue PREVIEW_SYNC_MAX_DISTANCE;

    /** 客户端快照缓存的软上限（KB），超出按 LRU 淘汰。 */
    public static final ModConfigSpec.IntValue PREVIEW_CACHE_MAX_KB;

    /**
     * 物品侧体积兜底：单个工厂物品的方块实体 NBT（含整份微缩快照）超过该值（KB）时逐级瘦身。
     *
     * <p>物品侧没有别的闸门：NBT 随 ItemStack 走进容器/槽位/实体同步包，而客户端读包内 NBT 有
     * 2 MB 硬配额（{@code FriendlyByteBuf.DEFAULT_NBT_QUOTA}），单品越界 = 收包方解析失败/断线。
     */
    public static final ModConfigSpec.IntValue PREVIEW_ITEM_MAX_KB;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment(
                "CreateCMPOR 通用配置",
                "CreateCMPOR General Configuration").push("stress");
        ENABLE_STRESS_OUTPUT = builder
                .comment(
                        "是否允许应力 IO 方块向外提供应力。",
                        "Whether stress I/O blocks are allowed to provide stress outward.",
                        "true：应力 > 0 时向外提供（输出模式），同时处理 < 0 的输入。",
                        "true: provide stress outward when stress > 0 (output mode), also handle input when < 0.",
                        "false：禁止输出，仅在应力 < 0 时从外部吸取，绝不向外提供。",
                        "false: never provide outward, only draw from outside when stress < 0.")
                .define("enableStressOutput", true);
        DEV_MANUAL_ACTIVATION = builder
                .comment(
                        "开发期：是否允许空手右键手动激活/停用应力 IO 方块。",
                        "Dev-only: allow empty-hand right-click to manually toggle stress I/O blocks.",
                        "true（开发期）：保留右键手动切换，便于测试。",
                        "true (dev): keep right-click manual toggle for testing.",
                        "false（默认，生产）：仅由本模组评估流程自动激活，右键无效。",
                        "false (default, production): activated only by the evaluation flow; right-click does nothing.")
                .define("devManualActivation", false);
        STRESS_LOSS_FACTOR = builder
                .comment(
                        "应力输出损耗系数（0~1，默认 0.1）。",
                        "Stress output loss factor (0~1, default 0.1).",
                        "应力拓展方块对外输出应力时：实际输出 = 工厂可提供应力 ×(1 - stressLossFactor)。",
                        "When the stress extension block outputs stress: actual = factory available ×(1 - stressLossFactor).")
                .defineInRange("stressLossFactor", 0.1, 0.0, 1.0);
        builder.pop();

        builder.comment(
                "评估安全配置",
                "Evaluation Security Configuration").push("evaluation");
        ENABLE_INVENTORY_AUDIT = builder
                .comment(
                        "是否启用评估前后库存守恒审计。",
                        "Enable inventory conservation audit before/after evaluation.")
                .define("enableInventoryAudit", true);
        SUSPICIOUS_MODS = builder
                .comment(
                        "模组级黑名单：该命名空间下的任何方块或实体进入房间即拒绝评估。",
                        "用于 capability 不可审计的存储模组（AE2/RS 等）——先把模组 ID 填到这里，",
                        "再按需用 suspiciousBlocks 放行个别方块。",
                        "默认空列表：AE2 方块本身已由 ContainerItemExpander 兼容读取，故不再默认拒绝。",
                        "Mod-level blacklist: any block or entity in this namespace aborts the evaluation.",
                        "For storage mods whose capabilities cannot be audited (AE2/RS, etc.).",
                        "Empty by default: AE2 blocks are already read by the built-in compat layer.")
                .defineListAllowEmpty("suspiciousMods", List.of(), value -> value instanceof String);
        SUSPICIOUS_BLOCKS = builder
                .comment(
                        "明确禁止进入评估的方块 ID（namespace:path）。",
                        "Block IDs explicitly forbidden from entering evaluation.")
                .defineListAllowEmpty("suspiciousBlocks", List.of(), value -> value instanceof String);
        SUSPICIOUS_ITEMS = builder
                .comment(
                        "明确禁止进入评估的物品 ID（namespace:path）——物品级黑名单。",
                        "检查范围：容器槽位物品、嵌套容器内部物品（潜影盒/AE2 cell 等）、",
                        "地板掉落物、contraption 携带物。命中即中止评估。",
                        "Item IDs explicitly forbidden from entering evaluation.",
                        "Covers container slots, nested container contents, dropped items and contraption cargo.")
                .defineListAllowEmpty("suspiciousItems", List.of(), value -> value instanceof String);
        MAX_CONCURRENT_EVALUATIONS = builder
                .comment(
                        "同时进行区块副本克隆的评估会话上限；超出后按创建顺序排队。",
                        "Max concurrent evaluation sessions cloning replica chunks; extra ones queue in creation order.")
                .defineInRange("maxConcurrentEvaluations", 4, 1, 16);
        EVALUATE_SECONDS = builder
                .comment(
                        "评估时长（秒）。",
                        "Evaluation duration (seconds).")
                .defineInRange("evaluateSeconds", 60, 1, 3600);
        RECORD_START = builder
                .comment(
                        "评估开始时的预热秒数（该时段不计入结果）。",
                        "Warmup seconds at evaluation start (not counted in the result).")
                .defineInRange("recordStart", 60, 0, 600);
        EVALUATION_MODE = builder
                .comment(
                        "评估模式：AUTO 自动判定，FORCE_RATE 强制速率拟合，FORCE_REPLAY 强制录制回放。",
                        "Evaluation mode: AUTO auto-decide, FORCE_RATE force rate fitting, FORCE_REPLAY force recorded playback.")
                .defineListAllowEmpty("evaluationMode", List.of("AUTO"), value -> value instanceof String);
        LOSS_RATE = builder
                .comment(
                        "产出损耗护栏（0-1）：回放/速率结果乘以该系数，默认 0.95 即扣 5%。",
                        "Output loss guardrail (0-1): replay/rate results are multiplied by this factor; default 0.95 = 5% deduction.")
                .defineInRange("lossRate", 0.95, 0.0, 1.0);
        INTERMEDIATE_RATIO = builder
                .comment(
                        "中间产物过滤比例：总量低于 评估秒数×该比例 的条目视为中间产物。",
                        "Intermediate product filter ratio: entries below (evaluation seconds × this ratio) are treated as intermediates.")
                .defineInRange("intermediateRatio", 0.25, 0.0, 1.0);
        IO_ERROR_RATIO = builder
                .comment(
                        "动态噪声阈值比例：|净产出| < max(产出,消耗)×该比例 视为噪声。",
                        "Dynamic noise threshold ratio: |net output| < max(output, consumption) × this ratio is treated as noise.")
                .defineInRange("ioErrorRatio", 0.001, 0.0, 1.0);
        CATALYST_ITEMS = builder
                .comment(
                        "催化剂保留清单：这些物品即使量小也保留为输入（不按中间产物删除）。",
                        "Catalyst keep-list: these items are kept as inputs even in small amounts (not removed as intermediates).")
                .defineListAllowEmpty("catalystItems", List.of(), value -> value instanceof String);
        DEDUP_BLOCKS = builder
                .comment(
                        "库存扫描需要去重的方块 ID（多视图容器防重复计数）。",
                        "Block IDs that need de-duplication during inventory scan (prevent double counting for multi-view containers).",
                        "Storage Drawers 压缩抽屉会由代码自动识别，此列表可补充其他模组方块。",
                        "Storage Drawers compacting drawers are auto-detected; add other mod blocks here.")
                .defineListAllowEmpty("dedupBlocks",
                        List.of(
                                "storagedrawers:compacting_drawers_2",
                                "storagedrawers:compacting_drawers_3",
                                "storagedrawers:compacting_half_drawers_2",
                                "storagedrawers:compacting_half_drawers_3",
                                "storagedrawers:framed_compacting_drawers_2",
                                "storagedrawers:framed_compacting_drawers_3",
                                "storagedrawers:framed_compacting_half_drawers_2",
                                "storagedrawers:framed_compacting_half_drawers_3"),
                        value -> value instanceof String);
        ENERGY_STABILITY_RELAXATION = builder
                .comment(
                        "能量条目的稳定性容差放宽倍数（能量网络波动大，避免误判为不稳定）。",
                        "Stability tolerance relaxation multiplier for energy entries (energy networks fluctuate; avoid false instability).")
                .defineInRange("energyStabilityRelaxation", 2.0, 1.0, 10.0);
        STRESS_STABILITY_RELAXATION = builder
                .comment(
                        "应力条目的稳定性容差放宽倍数。",
                        "Stability tolerance relaxation multiplier for stress entries.")
                .defineInRange("stressStabilityRelaxation", 2.0, 1.0, 10.0);
        ENABLE_FACTORY_REVERT = builder
                .comment(
                        "是否允许启动棒把工厂还原为原 CompactMachines 机器（新版无库存复制风险，默认开启）。",
                        "Allow the launcher stick to revert the factory back to the original CompactMachines machine (no item duplication risk in the new version; enabled by default).")
                .define("enableFactoryRevert", true);
        CONTINUE_ON_SOURCE_RELOADED = builder
                .comment(
                        "源房间冻结成功后其区块又被其他机制重新加载时，是否跳过 idle 复检直接继续评估。",
                        "If the frozen source room's chunks get re-loaded again, skip the idle re-check and continue the evaluation anyway.",
                        "true：卸载确认成功后即使源区块被复载也继续（快照可能非严格冻结；仅当环境无法让房间保持卸载时用于解除阻塞）。",
                        "true: after a successful unload, continue even if source chunks are re-loaded (snapshot may not be strictly frozen; use only when the environment refuses to keep the room unloaded).",
                        "false（默认，严格）：卸载后源区块再次加载立即安全取消。",
                        "false (default, strict): cancel safely as soon as source chunks load again after unload.")
                .define("continueOnSourceReloaded", false);
        UNLOAD_STUCK_PENDING_TICKS = builder
                .comment(
                        "源房间区块卡在 vanilla 卸载队列（pendingUnloads）时的容忍 tick 数。",
                        "Ticks to tolerate a source chunk stuck in vanilla's pending-unload queue.",
                        "0（默认）：严格——卡住即视为未卸载，超时后冻结失败回滚。",
                        "0 (default): strict — a stuck chunk counts as not unloaded; freezing fails and rolls back after the timeout.",
                        ">0：连续该 tick 数观察到「仅 pendingUnloads 卡住、其余子条件全空」时视为已卸载并继续冻结。",
                        ">0: after this many consecutive ticks of 'only pendingUnloads stuck, all other sub-conditions clear', treat it as unloaded and continue freezing.")
                .defineInRange("unloadStuckPendingTicks", 0, 0, 100000);
        ENABLE_EVALUATION_OBSERVATION = builder
                .comment(
                        "是否允许玩家以观察者模式进入评估副本查看产线。",
                        "Whether players may enter a running evaluation replica as a spectator.",
                        "true（默认）：手持 personal_shrinking_device 右键评估方块进入；被传送指令送入评估维度的玩家也会自动转为观察者。",
                        "true (default): right-click the evaluator block with personal_shrinking_device; players teleported into an evaluation dimension are converted to spectators automatically.",
                        "false：关闭观察功能。",
                        "false: disable observation entirely.",
                        "观察者不产生区块票据、不参与刷怪，因此不影响评估结果。",
                        "Spectators generate no chunk tickets and never participate in mob spawning, so evaluation results are unaffected.")
                .define("enableEvaluationObservation", true);
        OBSERVATION_PERMISSION_LEVEL = builder
                .comment(
                        "允许观察评估副本的权限等级（0=所有玩家，2=仅管理员）。",
                        "Permission level required to observe an evaluation replica (0 = everyone, 2 = operators only).")
                .defineInRange("observationPermissionLevel", 0, 0, 4);
        builder.pop();

        builder.comment(
                "工厂方块微缩预览（0.4.0）",
                "Factory miniature preview (0.4.0)").push("preview");
        ENABLE_FACTORY_PREVIEW = builder
                .comment(
                        "是否在固化时采集房间产线快照，供工厂方块渲染微缩预览。",
                        "Whether to capture a room snapshot at solidify time for the factory's miniature preview.",
                        "true（默认）：固化那一刻从评估副本采集；采集失败只影响预览，不影响固化。",
                        "true (default): captured from the evaluation replica at solidify time; failures never affect solidification.",
                        "false：不采集，工厂外观与旧版一致。",
                        "false: no capture; factories look like previous versions.")
                .define("enableFactoryPreview", true);
        ENABLE_FACTORY_ITEM_PREVIEW = builder
                .comment(
                        "手持/物品栏里的工厂物品是否显示它自己携带的微缩预览（0.4.2）。",
                        "Whether the held / inventory factory item renders its own miniature preview (0.4.2).",
                        "true（默认）：物品显示自带预览（与方块侧同一套烘焙/绘制管线，共用缓存）；",
                        "true (default): the item shows its own preview using the same bake/render pipeline as the block;",
                        "false：物品只画机壳（与 0.4.1 外观一致），也不烘焙、不占物品侧缓存。",
                        "false: shell only (identical to 0.4.1), no baking and no item-side cache use.",
                        "纯客户端开关：不需要服务端同步，也不影响已采集的快照数据。",
                        "Client-only switch: no server sync required and captured snapshots are unaffected.")
                .define("enableFactoryItemPreview", true);
        PREVIEW_MAX_VOLUME = builder
                .comment(
                        "微缩预览网格体积上限（格）。超过该体积的房间先按整数步长盒式降采样再进快照。",
                        "Maximum preview grid volume in cells; larger rooms are box-filtered down before snapshotting.",
                        "该值同时决定工厂方块实体的 NBT 增量与客户端同步包大小（约 1 字节/格 + 调色板文本）。",
                        "This value bounds the block entity NBT growth and the client sync packet size.",
                        "默认 8192（约等于 20×20×20）。",
                        "Default 8192 (about 20x20x20).")
                .defineInRange("previewMaxVolume", 8192, 64, 32768);
        PREVIEW_ANIMATION_SECONDS = builder
                .comment(
                        "微缩预览动画的循环时长（秒）。0 = 静态预览（完全不采集转速，快照里不写速度表）。",
                        "Loop duration of the miniature preview animation, in seconds. 0 = static preview"
                                + " (no rotation speed is captured at all, so no speed table is written).",
                        "0：不采集转速 → 快照最小（省同步带宽），预览完全不转。",
                        "0: no speeds captured -> smallest snapshot (saves sync bandwidth), nothing rotates.",
                        ">0：采集转速并让纯旋转部件动起来；该值同时是速度整表缩放的目标周期"
                                + "（目标最大转速 = 60/秒数 RPM，整表统一缩放以保留传动比与啮合相位）。",
                        ">0: capture speeds and animate pure-rotation parts; this value is also the normalized"
                                + " revolution period (target max speed = 60/seconds RPM, applied table-wide).",
                        "默认 4 秒（约 4 秒转一圈的可视循环）；范围 0~30。",
                        "Default 4 seconds; range 0-30.")
                .defineInRange("factoryPreviewAnimationSeconds", 4, 0, 30);
        PREVIEW_MAX_ENTITIES = builder
                .comment(
                        "微缩预览里的实体条数上限（生物/掉落物/展示框…）。0 = 不采集实体（预览只有方块）。",
                        "Maximum entities kept in the miniature preview (mobs / dropped items / item frames)."
                                + " 0 = capture no entities (blocks only).",
                        "实体随快照一起同步，故超限时按「到取景盒中心的距离」确定性截断；",
                        "The entity table rides the same sync payload, so oversized rooms are truncated"
                                + " deterministically by distance to the focus centre.",
                        "玩家与机械动力装置（contraption）不在采集范围内。",
                        "Players and Create contraptions are never captured.",
                        "默认 48；范围 0~512。",
                        "Default 48; range 0-512.")
                .defineInRange("previewMaxEntities", 48, 0, 512);
        PREVIEW_MAX_CONTRAPTIONS = builder
                .comment(
                        "微缩预览里的机械动力装置（轴承/活塞/龙门/矿车装置/列车车厢…）条数上限。0 = 不采集装置。",
                        "Maximum Create contraptions (bearings / pistons / gantries / minecart & train contraptions)"
                                + " kept in the miniature preview. 0 = capture none.",
                        "装置的方块结构整个存在实体 NBT 里，单条体积远大于普通实体，故单独限流；",
                        "A contraption carries its whole block structure in entity NBT, so it is rate-limited"
                                + " separately from plain entities.",
                        "超限按「到取景盒中心的距离」确定性截断；单条裁剪后 > 16 KB 的装置整只丢弃。",
                        "Oversized rooms are truncated deterministically by distance; a single contraption whose"
                                + " trimmed NBT exceeds 16 KB is dropped entirely.",
                        "默认 8；范围 0~64。",
                        "Default 8; range 0-64.")
                .defineInRange("previewMaxContraptions", 8, 0, 64);
        PREVIEW_ENTITY_MAX_KB = builder
                .comment(
                        "微缩预览里单条普通实体的 NBT 预算（单位 KB）。超出即按「键体积从大到小」裁剪，裁剪只动大于"
                                + " previewTrimMinKeyKb 的键。",
                        "NBT budget per plain entity in the miniature preview snapshot (in KB). Oversized entries are"
                                + " trimmed key-by-key; only keys larger than previewTrimMinKeyKb are removed.",
                        "硬上限 = 该值 × 4：裁剪后仍超过才整只丢弃。",
                        "Hard limit = 4x this value: an entity is dropped only when it still exceeds that after trimming.",
                        "默认 8（KB）；范围 1~1024。",
                        "Default 8 (KB); range 1-1024.")
                .defineInRange("previewEntityMaxKb", 8, 1, 1024);
        PREVIEW_CONTRAPTION_MAX_KB = builder
                .comment(
                        "微缩预览里单条装置（contraption）的 NBT 预算（单位 KB）。装置把整套方块结构存在实体 NBT 的"
                                + " Contraption 复合里，实测可到 100 KB 以上。",
                        "NBT budget per Create contraption in the miniature preview (in KB). A contraption stores its"
                                + " whole block structure in entity NBT - 100 KB+ has been observed in practice.",
                        "Contraption 复合本身永不裁剪，所以该值实际是「其它键的裁剪门槛」，同时决定工厂 BE 的 NBT"
                                + " 与客户端同步包的体积增量。",
                        "The Contraption compound itself is never trimmed, so this value bounds the other keys and the"
                                + " resulting block-entity NBT / client sync payload growth.",
                        "硬上限 = 该值 × 4。默认 192（KB）；范围 4~1024"
                                + "（上限收紧到 1024：再大就可能把实体表顶过 2 MB 的客户端 NBT 配额）。",
                        "Hard limit = 4x this value. Default 192 (KB); range 4-1024"
                                + " (capped: larger values risk exceeding the client's 2 MB NBT quota).")
                .defineInRange("previewContraptionMaxKb", 192, 4, 1024);
        PREVIEW_TRIM_MIN_KEY_KB = builder
                .comment(
                        "裁剪时的「小键保护」阈值（单位 KB）：小于该体积的键一律保留。",
                        "Small-key protection threshold for trimming (in KB): keys smaller than this are never removed.",
                        "为什么不建议设 0：实测把装置的 Axis(38 字节) 丢掉后，Create 会静默不再旋转该装置"
                                + "（ControlledContraptionEntity.applyLocalTransforms 里 if (axis != null) 才旋转）。",
                        "Why 0 is discouraged: dropping a contraption's Axis (38 bytes) silently stops Create from"
                                + " rotating it (applyLocalTransforms only rotates when axis != null).",
                        "默认 1（KB）；范围 0~64（0 = 关闭保护）。",
                        "Default 1 (KB); range 0-64 (0 disables the protection).")
                .defineInRange("previewTrimMinKeyKb", 1, 0, 64);
        PREVIEW_BIG_ENTITY_WARN_KB = builder
                .comment(
                        "单条实体超过该体积时打一条日志（提醒快照/同步会明显变大），但不丢弃（单位 KB）。",
                        "Log when a single captured entity exceeds this size (payload grows noticeably); it is still kept (in KB).",
                        "默认 48（KB）；范围 1~8192。",
                        "Default 48 (KB); range 1-8192.")
                .defineInRange("previewBigEntityWarnKb", 48, 1, 8192);
        PREVIEW_ENTITY_TABLE_MAX_KB = builder
                .comment(
                        "快照里整张实体表的总体积上限（单位 KB）：超过就按「到取景盒中心的距离」丢弃剩余条目。",
                        "Total budget of the whole entity table in one snapshot (in KB); the rest is dropped by distance.",
                        "为什么必须有：实体表随方块实体 update tag 同步，而客户端读 NBT 有 2 MB 硬配额"
                                + "（FriendlyByteBuf.DEFAULT_NBT_QUOTA），越界会导致区块包解析失败/断线。",
                        "Why mandatory: the table rides the block-entity update tag and the client enforces a 2 MB NBT"
                                + " quota (FriendlyByteBuf.DEFAULT_NBT_QUOTA); exceeding it breaks chunk packets.",
                        "默认 1024（1 MB）；范围 64~1536。上限收到 1536 而不是 2048："
                                + "这条路会把快照随包一次性送给客户端（carryPreviewOnce / FULL 模式），"
                                + "贴着 2 MB 的 NBT 配额太险。配套地，previewContraptionMaxKb 的上限收到 1024。",
                        "Default 1024 (1 MB); range 64-1536. The cap is 1536 rather than 2048 because this path can"
                                + " send the snapshot in a single packet (carryPreviewOnce / FULL mode), which is too"
                                + " close to the 2 MB NBT quota. Consequently previewContraptionMaxKb caps at 1024.")
                .defineInRange("previewEntityTableMaxKb", 1024, 64, 1536);
        PREVIEW_ITEM_MAX_KB = builder
                .comment(
                        "物品侧体积兜底（单位 KB）：单个工厂物品的方块实体 NBT 超过它时逐级瘦身——"
                                + "先丢掉预览的快照实体段（装置/动物），仍越界则丢掉整份微缩预览。",
                        "Item-side size guard (in KB): when one factory item's block-entity NBT exceeds it, it is"
                                + " trimmed step by step - the snapshot entity section first, then the whole preview.",
                        "为什么需要：物品侧原先一道闸门都没有。NBT 随 ItemStack 走进容器/槽位/实体同步包，"
                                + "而客户端读包内 NBT 有 2 MB 硬配额（FriendlyByteBuf.DEFAULT_NBT_QUOTA），"
                                + "单品越界 = 收包方解析失败/断线（方块侧已有 previewSyncMaxKb，物品侧没有）。",
                        "Why: the item path previously had no gate at all. The NBT rides ItemStack sync packets while"
                                + " the client enforces a 2 MB NBT quota (FriendlyByteBuf.DEFAULT_NBT_QUOTA); one oversized"
                                + " item breaks the receiver (the block path is gated by previewSyncMaxKb, the item path"
                                + " was not).",
                        "正常工厂 ~130 KB，一分不动；默认 1024（1 MB）；范围 16~1792。"
                                + "设成 16 = 故意让正常工厂越界，用于自测这条兜底（会看到 INFO/WARN 日志）。",
                        "A normal factory is ~130 KB and is untouched. Default 1024 (1 MB); range 16-1792."
                                + " Setting 16 deliberately trips the guard on a normal factory for self-testing"
                                + " (an INFO/WARN line is logged).")
                .defineInRange("previewItemMaxKb", 1024, 16, 1792);
        PREVIEW_SYNC_MODE = builder
                .comment(
                        "服务端：微缩快照是否随方块实体 NBT 一起发给客户端。FULL = 随包发（旧行为）；"
                                + "ON_DEMAND = 只发轻量版本号，客户端要渲染时才索取（省流量）。",
                        "Server: whether the miniature snapshot rides the block-entity update tag. FULL = yes (old"
                                + " behaviour); ON_DEMAND = only a revision number, the client asks when it renders.",
                        "ON_DEMAND 下装旧版本本模组的客户端会静默看不到微缩（不崩不断线），"
                                + "服务端登录时检测到会自动整体回退到 FULL。",
                        "With ON_DEMAND an outdated client of this mod silently shows no miniature; the server detects"
                                + " that on login and falls back to FULL globally.")
                .defineEnum("previewSyncMode", PreviewSyncMode.ON_DEMAND);
        PREVIEW_REQUEST_MODE = builder
                .comment(
                        "客户端：渲染工厂时是否主动索取快照（本地缓存命中就不请求；服务端不支持时一次都不发）。",
                        "Client: whether to request the snapshot when rendering a factory (cache hits send nothing;"
                                + " nothing is sent when the server does not support it).")
                .defineEnum("previewRequestMode", PreviewSyncMode.ON_DEMAND);
        PREVIEW_REQUEST_RADIUS = builder
                .comment("客户端请求半径（格）。比渲染 LOD 小，避免在边缘反复请求。默认 48；范围 8~128。",
                        "Client request radius in blocks; smaller than the render LOD to avoid edge thrash."
                                + " Default 48; range 8-128.")
                .defineInRange("previewRequestRadius", 48, 8, 128);
        PREVIEW_REQUEST_COOLDOWN_TICKS = builder
                .comment("同一工厂两次请求的最小间隔（tick）。默认 20；范围 1~200。",
                        "Minimum ticks between two requests for the same factory. Default 20; range 1-200.")
                .defineInRange("previewRequestCooldownTicks", 20, 1, 200);
        PREVIEW_REQUEST_MAX_ATTEMPTS = builder
                .comment("同一工厂最多尝试几次（指数退避），超过本会话放弃。默认 4；范围 1~16。",
                        "Max attempts per factory (exponential backoff), then give up for the session."
                                + " Default 4; range 1-16.")
                .defineInRange("previewRequestMaxAttempts", 4, 1, 16);
        PREVIEW_REQUEST_PER_TICK = builder
                .comment("每 tick 最多发起几个请求（一次性走进一堆工厂时的闸门）。默认 2；范围 1~16。",
                        "Max requests issued per tick (burst guard when many factories come into view)."
                                + " Default 2; range 1-16.")
                .defineInRange("previewRequestPerTick", 2, 1, 16);
        PREVIEW_SYNC_MAX_KB = builder
                .comment("单个响应包的体积上限（KB），超过则服务端回 TOO_BIG 不发（客户端读 NBT 有 2 MB 硬配额）。",
                        "Max size of a single response payload (KB); larger ones are answered with TOO_BIG"
                                + " (the client enforces a 2 MB NBT quota).",
                        "默认 1024（1 MB）；范围 16~2048。",
                        "Default 1024 (1 MB); range 16-2048.")
                .defineInRange("previewSyncMaxKb", 1024, 16, 2048);
        PREVIEW_SYNC_MAX_DISTANCE = builder
                .comment("服务端愿意响应请求的最大距离（格），比请求半径宽，防远程探测。默认 128；范围 16~512。",
                        "Server-side max distance to answer requests (blocks); wider than the request radius to"
                                + " prevent remote probing. Default 128; range 16-512.")
                .defineInRange("previewSyncMaxDistance", 128, 16, 512);
        PREVIEW_CACHE_MAX_KB = builder
                .comment("客户端快照缓存软上限（KB），超出按 LRU 淘汰。默认 16384（16 MB）；范围 1024~262144。",
                        "Soft cap of the client-side snapshot cache (KB); LRU eviction beyond it."
                                + " Default 16384 (16 MB); range 1024-262144.")
                .defineInRange("previewCacheMaxKb", 16384, 1024, 262144);
        builder.pop();

        SPEC = builder.build();
    }
}