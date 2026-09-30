package com.yansunsky.createcmpor.client;

import com.yansunsky.createcmpor.block.FactoryBlock;
import com.yansunsky.createcmpor.block.FactoryBlockEntity;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 工厂的接口轴视觉（六面开口版）。
 *
 * <p>六个方向的半轴实例在构造时各设置一次固定朝向
 * （{@code rotateToFace(SOUTH, d)} 且先 {@code rotation.identity()}），此后朝向永不变更——
 * 彻底绕开 rotateToFace 叠加式旋转的坑。运行时仅按对应面的布尔属性切换可见性。
 *
 * <p>blockstate 变化（扳手开口/收起）时 Flywheel 会删除并重建 visual，可见性随重建刷新。
 */
public class FactoryVisual extends KineticBlockEntityVisual<FactoryBlockEntity> {

    private final Map<Direction, RotatingInstance> shafts = new EnumMap<>(Direction.class);

    public static FactoryVisual factory(VisualizationContext context, FactoryBlockEntity blockEntity,
                                        float partialTick) {
        return new FactoryVisual(context, blockEntity, partialTick);
    }

    private FactoryVisual(VisualizationContext context, FactoryBlockEntity blockEntity, float partialTick) {
        super(context, blockEntity, partialTick);
        BlockState state = blockState;
        for (Direction d : Direction.values()) {
            RotatingInstance instance = instancerProvider()
                    .instancer(AllInstanceTypes.ROTATING, Models.partial(AllPartialModels.SHAFT_HALF))
                    .createInstance();
            // 0.4.29：**逐面传该面自己的轴**（setup(be, axis) 重载）。
            // 旧实现用单参 setup(be) → KineticBlockEntityVisual.rotationAxis(state) → 全局 getRotationAxis
            // ⇒ 六个实例绕同一根轴自转，而几何朝向按面（rotateToFace）——多轴开口时非主轴的面会"翻滚"。
            // 参照实现：createadditionallogistics:flexible_shaft 的 FlexibleShaftVisual。
            instance.setup(blockEntity, d.getAxis())
                    .setPosition(getVisualPosition());
            // rotateToFace 是叠加式旋转：必须先重置单位四元数，防止实例池复用脏 rotation。
            instance.rotation.identity();
            instance.rotateToFace(Direction.SOUTH, d);
            instance.setVisible(shouldShowShaft(state, d));
            instance.setChanged();
            shafts.put(d, instance);
        }
    }

    /**
     * 该面是否显示传动杆（Flywheel 实例）。
     *
     * <p>展示模式（{@code encased=false}，0.4.0）的底面短轴由 vanilla BER 路径缩放渲染
     * （{@code FactoryRenderer}，把 8px 半轴压进 3px 底座），所以这里六个面**全部不显示**，
     * 免得 Flywheel 再画一根 8px 的长轴戳进展示区。
     */
    private static boolean shouldShowShaft(BlockState state, Direction d) {
        if (!state.getValue(FactoryBlock.ENCASED)) {
            return false;
        }
        return FactoryBlock.isShaftFaceOpen(state, d);
    }

    @Override
    public void update(float pt) {
        // 朝向固定不变，这里只刷新转速与可见性（blockstate 变化由 Flywheel 重建 visual 处理）。
        BlockState state = blockEntity.getBlockState();
        for (Map.Entry<Direction, RotatingInstance> entry : shafts.entrySet()) {
            RotatingInstance instance = entry.getValue();
            boolean show = shouldShowShaft(state, entry.getKey());
            if (show) {
                instance.setup(blockEntity);
            }
            instance.setVisible(show);
            instance.setChanged();
        }
    }

    @Override
    public void updateLight(float partialTick) {
        for (RotatingInstance instance : shafts.values()) {
            relight(instance);
        }
    }

    @Override
    protected void _delete() {
        for (RotatingInstance instance : shafts.values()) {
            instance.delete();
        }
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        for (RotatingInstance instance : shafts.values()) {
            consumer.accept(instance);
        }
    }
}
