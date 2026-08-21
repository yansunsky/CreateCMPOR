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
            instance.setup(blockEntity)
                    .setPosition(getVisualPosition());
            // rotateToFace 是叠加式旋转：必须先重置单位四元数，防止实例池复用脏 rotation。
            instance.rotation.identity();
            instance.rotateToFace(Direction.SOUTH, d);
            BooleanProperty property = FactoryBlock.SHAFT_BY_FACE.get(d);
            instance.setVisible(state.getValue(property));
            instance.setChanged();
            shafts.put(d, instance);
        }
    }

    @Override
    public void update(float pt) {
        // 朝向固定不变，这里只刷新转速与可见性（blockstate 变化由 Flywheel 重建 visual 处理）。
        BlockState state = blockEntity.getBlockState();
        for (Map.Entry<Direction, RotatingInstance> entry : shafts.entrySet()) {
            RotatingInstance instance = entry.getValue();
            boolean show = state.getValue(FactoryBlock.SHAFT_BY_FACE.get(entry.getKey()));
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
