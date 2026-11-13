package com.yansunsky.createcmpor.gametest;

import com.yansunsky.createcmpor.block.FactoryBlock;
import com.yansunsky.createcmpor.block.FactoryBlockEntity;
import com.yansunsky.createcmpor.block.IOExtensionBlock;
import com.yansunsky.createcmpor.block.IOExtensionBlockEntity;
import com.yansunsky.createcmpor.block.IOExtensionHandlerProxy;
import com.yansunsky.createcmpor.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Development-only tests for real registered blocks and capability providers in a ServerLevel.
 * Each test owns its own io_fixture structure and completes synchronously, without players,
 * production ticks, external commands, or changes outside that structure.
 *
 * Factory NBT is fixture setup through the normal public load API; assertions exercise the
 * placed block entities and actual item/fluid/energy handlers, not a simulated inventory.
 * These tests do not cover Compact Machines room cloning, physical transporters, real chunk
 * unloading/reloading, player tickets, observers, or kinetic production across ticks.
 */
@GameTestHolder("createcmpor")
@PrefixGameTestTemplate(false)
public final class IOExtensionGameTests {
    private static final String TEMPLATE = "io_fixture";
    private static final BlockPos FACTORY = new BlockPos(2, 2, 2);
    private static final BlockPos EXTENSION = new BlockPos(3, 2, 2);
    private static final Direction IO_SIDE = Direction.NORTH;

    private IOExtensionGameTests() {
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void headless_smoke(GameTestHelper helper) {
        helper.assertTrue(!Boolean.getBoolean("createcmpor.riskTestControl"),
                "Intentional GameTest negative control");
        helper.assertTrue(!helper.getLevel().isClientSide(), "Fixture must run in a ServerLevel");
        FactoryBlockEntity factory = placeFactory(helper, 0);
        IOExtensionBlockEntity extension = placeExtension(helper, EXTENSION, Direction.Axis.X);
        helper.assertTrue(extension.getReachableFactories().contains(helper.absolutePos(FACTORY)),
                "Placed extension must find the real registered factory");
        Ports ports = cachedPorts(helper, EXTENSION);
        helper.assertTrue(ports.items().getSlots() == 2, "Factory must expose input and output slots");
        helper.assertTrue(ports.fluids().getTanks() == 2, "Factory must expose input and output tanks");
        helper.assertTrue(ports.energy().getMaxEnergyStored() == 8192, "Factory must expose energy storage");
        helper.assertTrue(helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(FACTORY), Direction.NORTH) == factory.getItemHandler(),
                "Factory's own provider must expose the same real inventory");
        helper.assertTrue(helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(EXTENSION), Direction.EAST) == null,
                "An extension shaft face must not expose item IO");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void item_fluid_energy_simulation_and_execution(GameTestHelper helper) {
        FactoryBlockEntity factory = placeFactory(helper, 0);
        placeExtension(helper, EXTENSION, Direction.Axis.X);
        Ports ports = cachedPorts(helper, EXTENSION);
        helper.assertTrue(ports.items().insertItem(0, new ItemStack(Items.IRON_INGOT, 6), true).isEmpty(),
                "Simulated item insert must accept the fixture input");
        helper.assertTrue(factory.getItemHandler().getStackInSlot(0).isEmpty(),
                "Simulated item insert must not change the actual factory");
        helper.assertTrue(ports.items().insertItem(0, new ItemStack(Items.IRON_INGOT, 6), false).isEmpty(),
                "Executed item insert must accept the fixture input");
        helper.assertTrue(factory.getItemHandler().getStackInSlot(0).getCount() == 6,
                "Executed input must reach the actual factory exactly once");
        helper.assertTrue(ports.items().extractItem(1, 3, true).getCount() == 3
                && factory.getItemHandler().getStackInSlot(1).getCount() == 9,
                "Simulated item extract must leave factory output unchanged");
        helper.assertTrue(ports.items().extractItem(1, 3, false).getCount() == 3
                && factory.getItemHandler().getStackInSlot(1).getCount() == 6,
                "Executed item extract must debit actual output exactly once");

        FluidStack water = new FluidStack(Fluids.WATER, 250);
        helper.assertTrue(ports.fluids().fill(water, IFluidHandler.FluidAction.SIMULATE) == 250
                && factory.getFluidHandler().getFluidInTank(0).isEmpty(),
                "Simulated fluid fill must not change the factory");
        helper.assertTrue(ports.fluids().fill(water, IFluidHandler.FluidAction.EXECUTE) == 250
                && factory.getFluidHandler().getFluidInTank(0).getAmount() == 250,
                "Executed fluid fill must reach the factory exactly once");
        FluidStack lava = new FluidStack(Fluids.LAVA, 200);
        helper.assertTrue(ports.fluids().drain(lava, IFluidHandler.FluidAction.SIMULATE).getAmount() == 200
                && factory.getFluidHandler().getFluidInTank(1).getAmount() == 900,
                "Simulated drain must leave factory output unchanged");
        helper.assertTrue(ports.fluids().drain(lava, IFluidHandler.FluidAction.EXECUTE).getAmount() == 200
                && factory.getFluidHandler().getFluidInTank(1).getAmount() == 700,
                "Executed drain must debit actual output exactly once");

        helper.assertTrue(ports.energy().receiveEnergy(500, true) == 500
                && factory.getEnergyHandler().getEnergyStored() == 800,
                "Simulated energy input must not change stored energy");
        helper.assertTrue(ports.energy().receiveEnergy(500, false) == 500
                && factory.getEnergyHandler().getEnergyStored() == 1300,
                "Executed energy input must credit the factory exactly once");
        helper.assertTrue(ports.energy().extractEnergy(200, true) == 200
                && factory.getEnergyHandler().getEnergyStored() == 1300,
                "Simulated energy output must leave storage unchanged");
        helper.assertTrue(ports.energy().extractEnergy(200, false) == 200
                && factory.getEnergyHandler().getEnergyStored() == 1100,
                "Executed energy output must debit the factory exactly once");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void initially_missing_factory_recovers_without_requery(GameTestHelper helper) {
        placeExtension(helper, EXTENSION, Direction.Axis.X);
        // Initial queries intentionally go through BlockCapabilityCache, including the no-target case.
        Ports retained = cachedPorts(helper, EXTENSION);
        assertUnavailable(helper, retained, "Before the factory exists");
        FactoryBlockEntity factory = placeFactory(helper, 11);
        assertAvailable(helper, retained, 11, "Original cached handlers after target appears");
        helper.assertTrue(retained.items().insertItem(0, new ItemStack(Items.IRON_INGOT, 4), false).isEmpty()
                && factory.getItemHandler().getStackInSlot(0).getCount() == 15,
                "Original handler must resume real insertion without another capability lookup");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void removed_and_replaced_factory_rebinds_retained_handlers(GameTestHelper helper) {
        FactoryBlockEntity original = placeFactory(helper, 7);
        placeExtension(helper, EXTENSION, Direction.Axis.X);
        Ports retained = cachedPorts(helper, EXTENSION);
        helper.setBlock(FACTORY, Blocks.AIR);
        assertUnavailable(helper, retained, "After target removal");
        FactoryBlockEntity replacement = placeFactory(helper, 13);
        helper.assertTrue(original != replacement && original.isRemoved(),
                "Replacement must use a different live block entity");
        assertAvailable(helper, retained, 13, "After replacing the remote factory");
        helper.assertTrue(retained.items().insertItem(0, new ItemStack(Items.IRON_INGOT, 5), false).isEmpty()
                && replacement.getItemHandler().getStackInSlot(0).getCount() == 18
                && original.getItemHandler().getStackInSlot(0).getCount() == 7,
                "Retained handler must mutate only the new factory, never the removed one");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void closed_factory_face_and_rotated_extension_fail_closed(GameTestHelper helper) {
        placeFactory(helper, 3);
        placeExtension(helper, EXTENSION, Direction.Axis.X);
        Ports retained = cachedPorts(helper, EXTENSION);
        helper.setBlock(FACTORY, factoryState().setValue(FactoryBlock.SHAFT_EAST, false));
        assertUnavailable(helper, retained, "While the factory connection is closed");
        helper.setBlock(FACTORY, factoryState());
        assertAvailable(helper, retained, 3, "After reopening the factory connection");
        helper.setBlock(EXTENSION, extensionState(Direction.Axis.Z));
        assertUnavailable(helper, retained, "When the cached NORTH face becomes a shaft face");
        helper.setBlock(EXTENSION, extensionState(Direction.Axis.X));
        assertAvailable(helper, retained, 3, "After rotating the extension back");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void replaced_extension_does_not_reactivate_old_owner(GameTestHelper helper) {
        FactoryBlockEntity factory = placeFactory(helper, 5);
        IOExtensionBlockEntity original = placeExtension(helper, EXTENSION, Direction.Axis.X);
        Ports retained = cachedPorts(helper, EXTENSION);
        helper.setBlock(EXTENSION, Blocks.AIR);
        IOExtensionBlockEntity replacement = placeExtension(helper, EXTENSION, Direction.Axis.X);
        helper.assertTrue(original != replacement && original.isRemoved(),
                "Extension replacement must use a different owner identity");
        assertUnavailable(helper, retained, "Old owner at a reused coordinate");
        helper.assertTrue(factory.getItemHandler().getStackInSlot(0).getCount() == 5,
                "Rejected old-owner operations must not reach the live factory");
        assertAvailable(helper, cachedPorts(helper, EXTENSION), 5, "New owner's capabilities");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aligned_extension_chain_reaches_one_factory(GameTestHelper helper) {
        FactoryBlockEntity factory = placeFactory(helper, 2);
        placeExtension(helper, EXTENSION, Direction.Axis.X);
        BlockPos end = EXTENSION.east();
        IOExtensionBlockEntity extension = placeExtension(helper, end, Direction.Axis.X);
        helper.assertTrue(extension.getReachableFactories().size() == 1
                && extension.getReachableFactories().contains(helper.absolutePos(FACTORY)),
                "Aligned chain must reach exactly its one actual factory");
        Ports ports = cachedPorts(helper, end);
        helper.assertTrue(ports.items().insertItem(0, new ItemStack(Items.IRON_INGOT, 4), false).isEmpty()
                && factory.getItemHandler().getStackInSlot(0).getCount() == 6,
                "Indirect forwarding must mutate the same real inventory exactly once");
        extension.checkSelfDestruct();
        helper.assertTrue(helper.getBlockEntity(end) == extension,
                "One-ended chain must survive the production self-destruction check");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void unloaded_owner_entry_guards_do_not_load_chunks(GameTestHelper helper) {
        // A real ServerLevel and production BE, intentionally NOT installed at this unloaded position.
        // This isolates entry guards; it is not proof of unloading an active chunk or BFS frontier guards.
        ServerLevel level = helper.getLevel();
        BlockPos unloaded = helper.absolutePos(BlockPos.ZERO).offset(1_000_000, 0, 1_000_000);
        helper.assertTrue(!level.isLoaded(unloaded) && !level.isLoaded(unloaded.east())
                && !level.isLoaded(unloaded.west()), "Remote entry-guard fixture must start unloaded");
        IOExtensionBlockEntity detached = new IOExtensionBlockEntity(unloaded, extensionState(Direction.Axis.X));
        detached.setLevel(level);
        Ports ports = new Ports(IOExtensionHandlerProxy.item(detached, IO_SIDE),
                IOExtensionHandlerProxy.fluid(detached, IO_SIDE), IOExtensionHandlerProxy.energy(detached, IO_SIDE));
        assertUnavailable(helper, ports, "Unloaded owner coordinate");
        helper.assertTrue(detached.getReachableFactories().isEmpty(), "Unloaded BFS root must have no targets");
        detached.checkSelfDestruct();
        helper.assertTrue(!level.isLoaded(unloaded) && !level.isLoaded(unloaded.east())
                && !level.isLoaded(unloaded.west()), "Production queries must leave the remote chunks unloaded");
        helper.succeed();
    }

    private static FactoryBlockEntity placeFactory(GameTestHelper helper, int inputAmount) {
        helper.setBlock(FACTORY, factoryState());
        FactoryBlockEntity factory = helper.getBlockEntity(FACTORY);
        CompoundTag tag = new CompoundTag();
        tag.put("input_items", storage("minecraft:iron_ingot", 64, inputAmount));
        tag.put("output_items", storage("minecraft:gold_ingot", 64, 9));
        tag.put("input_fluids", storage("minecraft:water", 4000, 0));
        tag.put("output_fluids", storage("minecraft:lava", 4000, 900));
        tag.putLong("input_energy_capacity", 4096);
        tag.putLong("output_energy_capacity", 4096);
        tag.putLong("input_energy_amount", 0);
        tag.putLong("output_energy_amount", 800);
        factory.loadWithComponents(tag, helper.getLevel().registryAccess());
        return factory;
    }

    private static CompoundTag storage(String id, long capacity, long amount) {
        CompoundTag entry = new CompoundTag();
        entry.putLong("capacity", capacity);
        entry.putLong("amount", amount);
        CompoundTag map = new CompoundTag();
        map.put(id, entry);
        return map;
    }

    private static BlockState factoryState() {
        return ModBlocks.FACTORY.get().defaultBlockState()
                .setValue(FactoryBlock.ENCASED, true).setValue(FactoryBlock.SHAFT_EAST, true);
    }

    private static BlockState extensionState(Direction.Axis axis) {
        return ModBlocks.IO_EXTENSION.get().defaultBlockState().setValue(IOExtensionBlock.AXIS, axis);
    }

    private static IOExtensionBlockEntity placeExtension(GameTestHelper helper, BlockPos position, Direction.Axis axis) {
        helper.setBlock(position, extensionState(axis));
        return helper.getBlockEntity(position);
    }

    private static Ports cachedPorts(GameTestHelper helper, BlockPos position) {
        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(position);
        var items = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, absolute, IO_SIDE);
        var fluids = BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, level, absolute, IO_SIDE);
        var energy = BlockCapabilityCache.create(Capabilities.EnergyStorage.BLOCK, level, absolute, IO_SIDE);
        Ports ports = new Ports(items.getCapability(), fluids.getCapability(), energy.getCapability());
        helper.assertTrue(ports.items() != null && ports.fluids() != null && ports.energy() != null,
                "An installed extension's valid IO face must return all dynamic proxies, even without a target");
        return ports;
    }

    private static void assertAvailable(GameTestHelper helper, Ports ports, int inputAmount, String phase) {
        helper.assertTrue(ports.items().getSlots() == 2
                && ports.items().getStackInSlot(0).getCount() == inputAmount,
                phase + ": retained item handler must see the live target");
        helper.assertTrue(ports.fluids().getTanks() == 2 && ports.fluids().getTankCapacity(0) == 4000,
                phase + ": retained fluid handler must see the live target");
        helper.assertTrue(ports.energy().getMaxEnergyStored() == 8192 && ports.energy().getEnergyStored() == 800,
                phase + ": retained energy handler must see the live target");
    }

    private static void assertUnavailable(GameTestHelper helper, Ports ports, String phase) {
        helper.assertTrue(ports.items().getSlots() == 0
                && ports.items().insertItem(0, new ItemStack(Items.IRON_INGOT, 4), false).getCount() == 4
                && ports.items().extractItem(0, 4, false).isEmpty(), phase + ": item IO must fail closed");
        helper.assertTrue(ports.fluids().getTanks() == 0
                && ports.fluids().fill(new FluidStack(Fluids.WATER, 100), IFluidHandler.FluidAction.EXECUTE) == 0
                && ports.fluids().drain(100, IFluidHandler.FluidAction.EXECUTE).isEmpty(),
                phase + ": fluid IO must fail closed");
        helper.assertTrue(ports.energy().getEnergyStored() == 0 && ports.energy().getMaxEnergyStored() == 0
                && ports.energy().receiveEnergy(100, false) == 0 && ports.energy().extractEnergy(100, false) == 0,
                phase + ": energy IO must fail closed");
    }

    private record Ports(IItemHandler items, IFluidHandler fluids, IEnergyStorage energy) {
    }
}
