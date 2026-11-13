package com.yansunsky.createcmpor.evaluation;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Real RATE/REPLAY functions with cold NBT fixtures; does not start a server or simulate logistics. */
public final class EvaluationBoundaryAuditTest {
    private static final AABB OUTER = new AABB(0, 64, 0, 9, 73, 9);
    private static final EvaluationRoomProjection.Bounds BOUNDS = EvaluationRoomProjection.Bounds.from(OUTER);
    private static final ChunkPos POS = new ChunkPos(0, 0);
    private static final ResourceLocation IRON_ID = ResourceLocation.parse("minecraft:iron_ingot");
    private static final EvaluationTrace.FlowKey IRON = EvaluationTrace.FlowKey.item(IRON_ID);
    private static final int SECONDS = 10;
    private static int checks;

    public static void main(String[] args) throws ReflectiveOperationException {
        // A fresh JVM needs an empty FML file list for NeoForge feature-flag bootstrap.
        // No mods, client, server or world are started.
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var defaults = com.electronwill.nightconfig.core.CommentedConfig.inMemory();
        com.yansunsky.createcmpor.Config.SPEC.correct(defaults);
        // FML 4.0.42 seals ILoadedConfig; its package-private record is the only implementation.
        // Use the verified constructor for this test JVM only; defaults are corrected before accept,
        // so no save/event callback or user configuration is touched.
        var configConstructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                .getDeclaredConstructor(com.electronwill.nightconfig.core.CommentedConfig.class,
                        java.nio.file.Path.class, net.neoforged.fml.config.ModConfig.class);
        configConstructor.setAccessible(true);
        com.yansunsky.createcmpor.Config.SPEC.acceptConfig(
                (net.neoforged.fml.config.IConfigSpec.ILoadedConfig)
                        configConstructor.newInstance(defaults, null, null));
        boolean unprojected = args.length > 0 && "--unprojected-control".equals(args[0]);
        auditContainerFixture("S0-empty exterior stock", true, false, false, unprojected);
        auditContainerFixture("same item inside and outside", true, true, false, unprojected);
        auditContainerFixture("nonempty S0 exterior stock", true, false, true, unprojected);
        auditContainerFixture("interior initial stock", false, true, false, unprojected);
        checkAudits("real production", EvaluationAudit.InventorySnapshot.empty(),
                EvaluationAudit.InventorySnapshot.empty(), 32, true);
        projectionMetadata();
        hashIntegrity();
        backpackIsolationAfterProjection();
        System.out.println("EvaluationBoundaryAuditTest passed: " + checks + " checks (no Minecraft world)");
    }

    private static void auditContainerFixture(String name, boolean outside, boolean inside, boolean buffer,
                                             boolean unprojected) {
        CompoundTag source = chunk(outside, inside, buffer);
        CompoundTag untouched = source.copy();
        CompoundTag projected = unprojected ? source.copy() : EvaluationRoomProjection.project(
                POS, source, new ListTag(), Optional.empty(), BOUNDS).chunk();
        check(source.equals(untouched), name + ": source NBT changed");
        EvaluationAudit.InventorySnapshot baseline = scanFixtureInventories(projected);
        // Represents inventory that could reach an output if the copied containers execute.
        // This is a trace fixture, not an emulation of Minecraft hopper or Create behavior.
        long output = allIronStock(projected);
        Map<String, Long> remaining = new HashMap<>(baseline.items());
        remaining.remove(IRON.signature());
        EvaluationAudit.InventorySnapshot end = new EvaluationAudit.InventorySnapshot(
                Map.copyOf(remaining), Map.of(), 0, 0, 0);
        checkAudits(name, baseline, end, output, false);
        if (!unprojected && outside) {
            check(projected.getList("block_entities", Tag.TAG_COMPOUND).stream()
                    .map(tag -> (CompoundTag) tag).noneMatch(be -> be.getInt("y") == 73),
                    name + ": outside container survived projection");
        }
    }

    private static void checkAudits(String name, EvaluationAudit.InventorySnapshot baseline,
                                   EvaluationAudit.InventorySnapshot end, long output, boolean production) {
        String room = "boundary-audit-" + checks;
        EvaluationTrace.Hub.INSTANCE.start(room, SECONDS, 0);
        EvaluationTrace trace = EvaluationTrace.Hub.INSTANCE.get(room);
        EvaluationTrace.Hub.INSTANCE.remove(room);
        if (output > 0) {
            trace.record(IRON, output, false, 20); // Formal sampling, not warmup.
        }
        var rate = EvaluationAudit.auditRates(baseline, baseline, end, trace, SECONDS, Map.of(), Map.of());
        double actualRate = rate.outputs().getOrDefault(IRON, 0.0);
        var replay = EvaluationReplay.build(trace, baseline, end, baseline, SECONDS, Map.of(), Map.of());
        long actualReplay = Arrays.stream(replay.replayOut().getOrDefault(IRON, new int[0]))
                .mapToLong(value -> value).sum();
        check(production ? actualRate > 0 : actualRate == 0,
                name + ": preloaded stock escaped accounting; RATE=" + actualRate + ", REPLAY=" + actualReplay);
        check(production ? actualReplay > 0 : actualReplay == 0,
                name + ": REPLAY leaked preloaded stock, count=" + actualReplay);
    }

    private static EvaluationAudit.InventorySnapshot scanFixtureInventories(CompoundTag chunk) {
        Map<String, Long> items = new HashMap<>();
        ListTag containers = chunk.getList("block_entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < containers.size(); i++) {
            CompoundTag be = containers.getCompound(i);
            if (!BOUNDS.containsBlock(be.getInt("x"), be.getInt("y"), be.getInt("z"))) {
                continue;
            }
            ListTag contents = be.getList("Items", Tag.TAG_COMPOUND);
            for (int slot = 0; slot < contents.size(); slot++) {
                CompoundTag item = contents.getCompound(slot);
                items.merge(item.getString("id"), (long) item.getInt("count"), Long::sum);
            }
        }
        return new EvaluationAudit.InventorySnapshot(Map.copyOf(items), Map.of(), 0, 0, 0);
    }

    private static long allIronStock(CompoundTag chunk) {
        long count = 0;
        ListTag containers = chunk.getList("block_entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < containers.size(); i++) {
            ListTag contents = containers.getCompound(i).getList("Items", Tag.TAG_COMPOUND);
            for (int slot = 0; slot < contents.size(); slot++) {
                CompoundTag item = contents.getCompound(slot);
                if (IRON.signature().equals(item.getString("id"))) {
                    count += item.getInt("count");
                }
            }
        }
        return count;
    }

    private static CompoundTag chunk(boolean outside, boolean inside, boolean buffer) {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", 3955);
        chunk.putInt("xPos", 0);
        chunk.putInt("zPos", 0);
        chunk.putInt("yPos", 0);
        chunk.putString("Status", "minecraft:full");
        ListTag blockEntities = new ListTag();
        SimpleBitStorage storage = new SimpleBitStorage(4, 4096);
        if (outside) {
            blockEntities.add(chest(4, 73, 4, IRON.signature(), 64));
            storage.set(index(4, 73, 4), 1);
        }
        if (inside) {
            blockEntities.add(chest(4, 68, 4, IRON.signature(), 64));
            storage.set(index(4, 68, 4), 1);
        }
        if (buffer) {
            blockEntities.add(chest(5, 68, 4, "minecraft:cobblestone", 1));
            storage.set(index(5, 68, 4), 1);
        }
        chunk.put("block_entities", blockEntities);
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        CompoundTag chest = new CompoundTag();
        chest.putString("Name", "minecraft:chest");
        ListTag palette = new ListTag();
        palette.add(air);
        palette.add(chest);
        CompoundTag states = new CompoundTag();
        states.put("palette", palette);
        states.putLongArray("data", storage.getRaw());
        CompoundTag biomes = new CompoundTag();
        ListTag biomePalette = new ListTag();
        biomePalette.add(StringTag.valueOf("minecraft:plains"));
        biomes.put("palette", biomePalette);
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 4);
        section.put("block_states", states);
        section.put("biomes", biomes);
        ListTag sections = new ListTag();
        sections.add(section);
        chunk.put("sections", sections);
        return chunk;
    }

    private static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    private static CompoundTag chest(int x, int y, int z, String itemId, int count) {
        CompoundTag item = new CompoundTag();
        item.putString("id", itemId);
        item.putInt("count", count);
        item.putByte("Slot", (byte) 0);
        ListTag items = new ListTag();
        items.add(item);
        CompoundTag be = new CompoundTag();
        be.putString("id", "minecraft:chest");
        be.putInt("x", x);
        be.putInt("y", y);
        be.putInt("z", z);
        be.put("Items", items);
        return be;
    }

    private static void projectionMetadata() {
        ResourceKey<Level> source = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("compactmachines:compact_world"));
        ResourceKey<Level> target = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("createcmpor:eval_world"));
        EvaluationManifest manifest = EvaluationManifest.create(UUID.randomUUID(), "fixture", source, target,
                0, List.of(POS), OUTER);
        EvaluationManifest restored = EvaluationManifest.load(manifest.save(), null);
        check(restored.roomProjectionBounds().equals(BOUNDS), "frozen bounds lost on journal roundtrip");
        check(restored.roomBounds().equals(OUTER), "frozen bounds disagree with scan AABB");
        check(restored.copyForBranchTarget(Level.NETHER).roomBounds().equals(OUTER),
                "parallel branch lost frozen bounds");
        CompoundTag projected = chunk(false, true, false);
        expectFailure(() -> restored.requireRoomProjection(projected), "unstamped staging accepted");
        EvaluationManifest.stampRoomProjection(projected, BOUNDS);
        restored.requireRoomProjection(projected);
        checks++;
        CompoundTag wrong = projected.copy();
        int[] changed = wrong.getCompound(EvaluationManifest.ROOM_PROJECTION_KEY).getIntArray("bounds").clone();
        changed[3]++;
        wrong.getCompound(EvaluationManifest.ROOM_PROJECTION_KEY).putIntArray("bounds", changed);
        expectFailure(() -> restored.requireRoomProjection(wrong), "staging from another boundary accepted");
        CompoundTag version = projected.copy();
        version.getCompound(EvaluationManifest.ROOM_PROJECTION_KEY).putInt("version", 0);
        expectFailure(() -> restored.requireRoomProjection(version), "legacy projection version accepted");
        CompoundTag malformed = projected.copy();
        malformed.getCompound(EvaluationManifest.ROOM_PROJECTION_KEY).putIntArray("bounds", new int[] {0});
        expectFailure(() -> restored.requireRoomProjection(malformed), "malformed projection accepted");
        CompoundTag legacyTag = manifest.save();
        legacyTag.remove(EvaluationManifest.ROOM_PROJECTION_KEY);
        EvaluationManifest legacy = EvaluationManifest.load(legacyTag, null);
        legacy.validate(); // Must remain readable so the existing cleanup-first recovery can run.
        check(!legacy.save().contains(EvaluationManifest.ROOM_PROJECTION_KEY), "legacy manifest was silently stamped");
        expectFailure(legacy::roomProjectionBounds, "legacy manifest may publish");
        expectFailure(() -> legacy.copyForBranchTarget(Level.NETHER), "legacy branch may publish");
        expectFailure(() -> legacy.requireRoomProjection(projected), "legacy manifest trusts marked chunk");
    }

    private static void hashIntegrity() {
        CompoundTag raw = chunk(true, true, false);
        String before = CanonicalNbtHasher.sha256(raw);
        CompoundTag projected = EvaluationRoomProjection.project(POS, raw, new ListTag(), Optional.empty(), BOUNDS).chunk();
        check(before.equals(CanonicalNbtHasher.sha256(raw)), "projection changed raw source hash");
        check(!before.equals(CanonicalNbtHasher.sha256(projected)), "raw and projected chunk hashes unexpectedly equal");
        CompoundTag withVersion = projected.copy();
        withVersion.putInt("Railways_DataVersion", 1);
        check(CanonicalNbtHasher.sha256(projected).equals(CanonicalNbtHasher.sha256(withVersion)),
                "ignored top-level field still changes canonical key count");
        withVersion.putInt("Railways_DataVersion", 2);
        check(CanonicalNbtHasher.sha256(projected).equals(CanonicalNbtHasher.sha256(withVersion)),
                "ignored top-level version changes hash");
        CompoundTag changed = projected.copy();
        changed.getList("block_entities", Tag.TAG_COMPOUND).getCompound(0)
                .getList("Items", Tag.TAG_COMPOUND).getCompound(0).putInt("count", 65);
        check(!CanonicalNbtHasher.sha256(projected).equals(CanonicalNbtHasher.sha256(changed)),
                "inventory corruption escaped strict staging hash");
        CompoundTag nested = new CompoundTag();
        nested.putInt("Railways_DataVersion", 1);
        projected.put("nested", nested);
        changed = projected.copy();
        changed.getCompound("nested").putInt("Railways_DataVersion", 2);
        check(!CanonicalNbtHasher.sha256(projected).equals(CanonicalNbtHasher.sha256(changed)),
                "nested field was incorrectly ignored");
        EvaluationManifest.stampRoomProjection(projected, BOUNDS);
        changed = projected.copy();
        changed.getCompound(EvaluationManifest.ROOM_PROJECTION_KEY).putInt("version", 2);
        check(!CanonicalNbtHasher.sha256(projected).equals(CanonicalNbtHasher.sha256(changed)),
                "projection marker corruption escaped staging hash");
        CompoundTag ordinary = new CompoundTag();
        ordinary.putString("session", "journal-check");
        ordinary.put("payload", projected);
        check(CanonicalNbtHasher.sha256(ordinary).equals(CanonicalNbtHasher.sha256(ordinary, Set.of())),
                "ordinary journal hash normalization changed");
    }

    private static void backpackIsolationAfterProjection() {
        CompoundTag source = chunk(true, true, false);
        UUID outside = UUID.randomUUID();
        UUID inside = UUID.randomUUID();
        String key = com.yansunsky.createcmpor.compat.inventory.SophisticatedBackpackIsolation.STORAGE_UUID_KEY;
        ListTag containers = source.getList("block_entities", Tag.TAG_COMPOUND);
        containers.getCompound(0).getList("Items", Tag.TAG_COMPOUND).getCompound(0).putUUID(key, outside);
        containers.getCompound(1).getList("Items", Tag.TAG_COMPOUND).getCompound(0).putUUID(key, inside);
        CompoundTag duplicate = new CompoundTag();
        duplicate.putUUID(key, inside);
        containers.getCompound(1).put("RepeatedReference", duplicate);
        CompoundTag original = source.copy();
        CompoundTag projected = EvaluationRoomProjection.project(POS, source, new ListTag(), Optional.empty(), BOUNDS).chunk();
        Map<UUID, UUID> mapping = new HashMap<>();
        com.yansunsky.createcmpor.compat.inventory.SophisticatedBackpackIsolation.rewriteUuids(projected, mapping);
        check(mapping.size() == 1 && mapping.containsKey(inside) && !mapping.containsKey(outside),
                "outside backpack was assigned a copy UUID before projection");
        CompoundTag retained = projected.getList("block_entities", Tag.TAG_COMPOUND).getCompound(0);
        UUID isolated = retained.getList("Items", Tag.TAG_COMPOUND).getCompound(0).getUUID(key);
        check(!isolated.equals(inside) && isolated.equals(mapping.get(inside)), "inside backpack UUID was not isolated");
        check(retained.getCompound("RepeatedReference").getUUID(key).equals(isolated),
                "duplicate backpack references lost their shared isolated UUID");
        check(source.equals(original), "UUID isolation changed source backpack data");
    }

    private static void expectFailure(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            checks++;
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
