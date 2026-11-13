package com.yansunsky.createcmpor.evaluation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.Arrays;
import java.util.Optional;

/** Cold-NBT regressions; no server, registry bootstrap or testing framework is required. */
public final class EvaluationRoomProjectionTest {
    private static final ChunkPos POS = new ChunkPos(0, 0);
    private static final EvaluationRoomProjection.Bounds ROOM =
            EvaluationRoomProjection.Bounds.from(new AABB(0, 64, 0, 9, 73, 9));
    private static final String WALL = "compactmachines:solid_wall";

    public static void main(String[] args) {
        if (args.length > 0 && "--unprojected-control".equals(args[0])) {
            Fixture fixture = fixture();
            // The old whole-chunk publication is exactly this unprojected bundle; it must fail.
            assertClosed(new EvaluationRoomProjection.Projection(
                    fixture.chunk, fixture.entities, fixture.poi));
            throw new AssertionError("unprojected control unexpectedly passed");
        }
        boundsMatchScan();
        preplacedOutsideInventoryIsRemoved();
        negativeCoordinatesAndAllSixFaces();
        vanillaPackedStorageBoundaries();
        paletteExpansionToFiveBits();
        singletonAndEntireOutsideSections();
        outsidePayloadIsRemovedBeforeValidation();
        projectionIsImmutableAndIdempotent();
        passengerGroupsRemainWhole();
        ordinaryEntityCompatibility();
        contraptionGeometryMustBeVerifiable();
        malformedRetainedDataFailsClosed();
        positionEdgesAndLegacyEntityRecords();
        System.out.println("EvaluationRoomProjectionTest: 13 groups passed (including unprojected negative control)");
    }

    private static void boundsMatchScan() {
        check(ROOM.equals(new EvaluationRoomProjection.Bounds(0, 64, 0, 8, 72, 8)),
                "walls must be included, maximum coordinates inclusive");
        var negative = EvaluationRoomProjection.Bounds.from(
                new AABB(-15.75, -30.5, -15.75, -6.000001, -20.000001, -6.000001));
        check(negative.equals(new EvaluationRoomProjection.Bounds(-16, -31, -16, -7, -21, -7)),
                "scan rounding must floor both endpoints, including negative fractional coordinates");
        check(ROOM.containsBlock(0, 64, 0) && ROOM.containsBlock(8, 72, 8), "both wall corners survive");
        check(!ROOM.containsBlock(9, 72, 8) && !ROOM.containsBlock(8, 73, 8), "first exterior layer is excluded");
        check(ROOM.containsPosition(8.999, 72.999, 8.999), "positions below the high face survive");
        check(!ROOM.containsPosition(9, 72, 8) && !ROOM.containsPosition(Double.NaN, 65, 1),
                "entity positions use a finite half-open volume");
        reject(() -> new EvaluationRoomProjection.Bounds(1, 0, 0, 0, 1, 1));
        reject(() -> EvaluationRoomProjection.Bounds.from(new AABB(Double.NaN, 0, 0, 1, 1, 1)));
    }

    private static void preplacedOutsideInventoryIsRemoved() {
        Fixture fixture = fixture();
        check(inventoryCount(fixture.chunk) == 135, "control must really contain preplaced outside inventories");
        expectAssertion(() -> assertClosed(new EvaluationRoomProjection.Projection(
                fixture.chunk, fixture.entities, fixture.poi)));
        var projected = fixture.project();
        assertClosed(projected);
        check(inventoryCount(projected.chunk()) == 7, "only inside stock may enter the clone");
        check(projected.entities().getCompound(0).getList("Items", Tag.TAG_COMPOUND)
                        .getCompound(0).getInt("count") == 3,
                "inside container-entity stock must remain intact");
    }

    private static void assertClosed(EvaluationRoomProjection.Projection projected) {
        check("minecraft:air".equals(block(projected.chunk(), 4, 73, 4)),
                "preplaced chest immediately above the roof survived whole-chunk publication");
        check("minecraft:air".equals(block(projected.chunk(), 4, 80, 4)),
                "whole vertical exterior section must become air");
        check("minecraft:chest".equals(block(projected.chunk(), 4, 66, 4)), "inside chest was lost");
        check(WALL.equals(block(projected.chunk(), 0, 66, 4)), "west wall was lost");
        check(WALL.equals(block(projected.chunk(), 8, 66, 4)), "east wall was lost");
        check(WALL.equals(block(projected.chunk(), 4, 64, 4)), "floor wall was lost");
        check(WALL.equals(block(projected.chunk(), 4, 72, 4)), "roof wall was lost");
        check(projected.chunk().getList("block_entities", Tag.TAG_COMPOUND).size() == 1,
                "outside block entities must not survive");
        check(projected.chunk().getList("block_ticks", Tag.TAG_COMPOUND).size() == 1,
                "outside block ticks must not survive");
        check(projected.chunk().getList("fluid_ticks", Tag.TAG_COMPOUND).size() == 1,
                "outside fluid ticks must not survive");
        check(projected.entities().size() == 2, "outside container entity must not survive");
        check(projected.poi().orElseThrow().getCompound("Sections").getCompound("4")
                        .getList("Records", Tag.TAG_COMPOUND).size() == 1,
                "outside POI must not survive");
        check(!projected.chunk().contains("Heightmaps") && !projected.chunk().contains("PostProcessing")
                        && !projected.chunk().contains("UpgradeData") && !projected.chunk().getBoolean("isLightOn"),
                "stale derived data must not survive");
        for (Tag entry : projected.chunk().getList("sections", Tag.TAG_COMPOUND)) {
            var section = (CompoundTag) entry;
            check(!section.contains("BlockLight") && !section.contains("SkyLight"), "stale section light survived");
        }
    }

    private static void negativeCoordinatesAndAllSixFaces() {
        var pos = new ChunkPos(-1, -1);
        var bounds = new EvaluationRoomProjection.Bounds(-12, -28, -12, -4, -20, -4);
        CompoundTag source = chunk(pos, section(-2, new String[]{"minecraft:stone"}, new int[4096]));
        var projected = EvaluationRoomProjection.project(pos, source, new ListTag(), Optional.empty(), bounds);
        for (int y = -32; y < -16; y++) {
            for (int z = -16; z < 0; z++) {
                for (int x = -16; x < 0; x++) {
                    String expected = bounds.containsBlock(x, y, z) ? "minecraft:stone" : "minecraft:air";
                    check(expected.equals(block(projected.chunk(), x, y, z)), "negative face mismatch at " + x + "," + y + "," + z);
                }
            }
        }
        check("minecraft:stone".equals(block(projected.chunk(), -12, -28, -12)), "minimum walls missing");
        check("minecraft:stone".equals(block(projected.chunk(), -4, -20, -4)), "maximum walls missing");
    }

    private static void vanillaPackedStorageBoundaries() {
        var full = new EvaluationRoomProjection.Bounds(0, 64, 0, 15, 79, 15);
        // 17 entries require 5 bits: index 11 ends one long, index 12 starts the next.
        // 257 entries use 9 local disk bits even when vanilla selects a global runtime palette.
        for (int size : new int[]{17, 257}) {
            String[] palette = new String[size];
            for (int i = 0; i < size; i++) {
                palette[i] = "projection_test:state_" + i;
            }
            int[] values = new int[4096];
            for (int i = 0; i < values.length; i++) {
                values[i] = i % size;
            }
            CompoundTag source = chunk(POS, section(4, palette, values));
            CompoundTag states = source.getList("sections", Tag.TAG_COMPOUND).getCompound(0).getCompound("block_states");
            int bits = size == 17 ? 5 : 9;
            check(states.getLongArray("data").length == (4096 + 64 / bits - 1) / (64 / bits),
                    "fixture must match 1.21.1 non-crossing long count");
            SimpleBitStorage vanilla = new SimpleBitStorage(bits, 4096, states.getLongArray("data"));
            check(vanilla.get(11) == 11 % size && vanilla.get(12) == 12 % size
                            && vanilla.get(4095) == 4095 % size,
                    "manual disk fixture must decode with vanilla SimpleBitStorage");
            var projected = EvaluationRoomProjection.project(POS, source, new ListTag(), Optional.empty(), full);
            for (int i = 0; i < values.length; i++) {
                check(palette[values[i]].equals(block(projected.chunk(), i & 15, 64 + (i >>> 8), (i >>> 4) & 15)),
                        "packed palette transition changed index " + i);
            }
            CompoundTag output = projected.chunk().getList("sections", Tag.TAG_COMPOUND).getCompound(0).getCompound("block_states");
            check(Arrays.equals(states.getLongArray("data"), output.getLongArray("data")),
                    "unchanged section must retain the exact vanilla packed sequence");
        }
    }

    private static void paletteExpansionToFiveBits() {
        String[] names = new String[16];
        for (int i = 0; i < names.length; i++) {
            names[i] = "projection_test:solid_" + i;
        }
        int[] values = new int[4096];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i >>> 4) & 15;
        }
        CompoundTag source = chunk(POS, section(4, names, values));
        var narrow = new EvaluationRoomProjection.Bounds(0, 64, 0, 14, 79, 15);
        var projected = EvaluationRoomProjection.project(POS, source, new ListTag(), Optional.empty(), narrow);
        CompoundTag states = projected.chunk().getList("sections", Tag.TAG_COMPOUND).getCompound(0).getCompound("block_states");
        check(states.getList("palette", Tag.TAG_COMPOUND).size() == 17
                        && states.getLongArray("data").length == 342,
                "adding AIR to sixteen retained states must repack four disk bits into five");
        for (int i = 0; i < values.length; i++) {
            String expected = (i & 15) == 15 ? "minecraft:air" : names[values[i]];
            check(expected.equals(block(projected.chunk(), i & 15, 64 + (i >>> 8), (i >>> 4) & 15)),
                    "palette expansion changed state " + i);
        }
    }

    private static void singletonAndEntireOutsideSections() {
        CompoundTag source = chunk(POS, section(4, new String[]{"minecraft:stone"}, new int[4096]));
        var full = new EvaluationRoomProjection.Bounds(0, 64, 0, 15, 79, 15);
        var kept = EvaluationRoomProjection.project(POS, source, new ListTag(), Optional.empty(), full);
        CompoundTag keptStates = kept.chunk().getList("sections", Tag.TAG_COMPOUND).getCompound(0).getCompound("block_states");
        check(keptStates.getList("palette", Tag.TAG_COMPOUND).size() == 1 && !keptStates.contains("data"),
                "singleton palette should omit packed data");
        var partial = EvaluationRoomProjection.project(POS, source, new ListTag(), Optional.empty(), ROOM);
        check("minecraft:stone".equals(block(partial.chunk(), 4, 66, 4))
                        && "minecraft:air".equals(block(partial.chunk(), 4, 73, 4)),
                "singleton partial section must gain an AIR entry");
        var outside = EvaluationRoomProjection.project(POS, source, new ListTag(), Optional.empty(),
                new EvaluationRoomProjection.Bounds(32, 64, 32, 40, 72, 40));
        CompoundTag air = outside.chunk().getList("sections", Tag.TAG_COMPOUND).getCompound(0).getCompound("block_states");
        check(air.getList("palette", Tag.TAG_COMPOUND).size() == 1 && !air.contains("data")
                        && "minecraft:air".equals(block(outside.chunk(), 4, 66, 4)),
                "entire outside chunk section must become a canonical AIR singleton");
    }

    private static void outsidePayloadIsRemovedBeforeValidation() {
        Fixture fixture = fixture();
        fixture.chunk.getList("block_entities", Tag.TAG_COMPOUND).getCompound(1).putString("id", "bad id");
        fixture.chunk.getList("block_ticks", Tag.TAG_COMPOUND).getCompound(1).remove("i");
        fixture.poi.orElseThrow().getCompound("Sections").getCompound("4")
                .getList("Records", Tag.TAG_COMPOUND).getCompound(1).putString("type", "bad id");
        CompoundTag section = fixture.chunk.getList("sections", Tag.TAG_COMPOUND).getCompound(0);
        CompoundTag states = section.getCompound("block_states");
        ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
        CompoundTag outsideOnly = new CompoundTag();
        outsideOnly.putString("Name", "bad outside state");
        palette.add(outsideOnly);
        SimpleBitStorage storage = new SimpleBitStorage(4, 4096, states.getLongArray("data").clone());
        storage.set(index(4, 73, 4), 3);
        states.putLongArray("data", storage.getRaw());
        var projected = fixture.project();
        assertClosed(projected);
        for (Tag entry : projected.chunk().getList("sections", Tag.TAG_COMPOUND)) {
            check(!((CompoundTag) entry).getCompound("block_states").getList("palette", Tag.TAG_COMPOUND)
                    .contains(outsideOnly), "unused outside palette entry must not reach later blacklist validation");
        }
    }

    private static void projectionIsImmutableAndIdempotent() {
        Fixture fixture = fixture();
        CompoundTag beforeChunk = fixture.chunk.copy();
        ListTag beforeEntities = fixture.entities.copy();
        CompoundTag beforePoi = fixture.poi.orElseThrow().copy();
        var first = fixture.project();
        var second = EvaluationRoomProjection.project(POS, first.chunk(), first.entities(), first.poi(), ROOM);
        check(first.chunk().equals(second.chunk()) && first.entities().equals(second.entities())
                        && first.poi().equals(second.poi()), "projection must be idempotent");
        check(fixture.chunk.equals(beforeChunk) && fixture.entities.equals(beforeEntities)
                        && fixture.poi.orElseThrow().equals(beforePoi), "projection modified source records");
        first.chunk().getList("block_entities", Tag.TAG_COMPOUND).getCompound(0)
                .getList("Items", Tag.TAG_COMPOUND).getCompound(0).putInt("count", 999);
        first.entities().getCompound(0).getList("Items", Tag.TAG_COMPOUND).getCompound(0).putInt("count", 999);
        first.poi().orElseThrow().getCompound("Sections").getCompound("4")
                .getList("Records", Tag.TAG_COMPOUND).getCompound(0).putString("type", "test:changed");
        check(fixture.chunk.equals(beforeChunk) && fixture.entities.equals(beforeEntities)
                        && fixture.poi.orElseThrow().equals(beforePoi), "projection shares mutable nested source tags");
    }

    private static void passengerGroupsRemainWhole() {
        CompoundTag inside = entity("test:vehicle", 4, 66, 4);
        inside.put("Passengers", list(entity("test:passenger", 4, 67, 4)));
        check(projectEntities(list(inside)).size() == 1, "inside passenger group was split or dropped");
        CompoundTag outside = entity("test:vehicle", 12, 66, 12);
        outside.put("Passengers", list(entity("test:passenger", 12, 67, 12)));
        check(projectEntities(list(outside)).isEmpty(), "outside passenger group survived");
        CompoundTag mixed = entity("test:vehicle", 4, 66, 4);
        mixed.put("Passengers", list(entity("test:passenger", 4, 73, 4)));
        reject(() -> projectEntities(list(mixed)));
        CompoundTag reverse = entity("test:vehicle", 4, 73, 4);
        reverse.put("Passengers", list(entity("test:passenger", 4, 66, 4)));
        reject(() -> projectEntities(list(reverse)));
        CompoundTag deep = entity("test:vehicle", 4, 66, 4);
        CompoundTag cursor = deep;
        for (int i = 0; i < 9; i++) {
            CompoundTag child = entity("test:passenger", 4, 66, 4);
            cursor.put("Passengers", list(child));
            cursor = child;
        }
        reject(() -> projectEntities(list(deep)));
    }

    private static void ordinaryEntityCompatibility() {
        ListTag entities = new ListTag();
        for (String id : new String[]{"minecraft:slime", "minecraft:phantom", "minecraft:painting",
                "minecraft:item_frame", "minecraft:block_display", "minecraft:interaction", "ordinary_mod:pet"}) {
            CompoundTag entity = entity(id, 4, 66, 4);
            entity.putInt("Size", 127);
            entity.putDouble("scale", 1000);
            entity.put("Contraption", new CompoundTag());
            entities.add(entity);
        }
        check(projectEntities(entities).equals(entities),
                "ordinary entities must use finite Pos without a type/size/scale whitelist");
    }

    private static void contraptionGeometryMustBeVerifiable() {
        CompoundTag inside = contraption("create:stationary_contraption", 4, 67, 4,
                new float[]{0, 0, 0, 1, 1, 1});
        check(projectEntities(list(inside)).size() == 1, "verifiable inside contraption was lost");
        CompoundTag oriented = contraption("create:contraption", 4, 67, 4,
                new float[]{0, 0, 0, 1, 1, 1});
        check(projectEntities(list(oriented)).size() == 1, "oriented contraption anchor was not handled");
        CompoundTag outside = contraption("create:stationary_contraption", 12, 67, 12,
                new float[]{0, 0, 0, 1, 1, 1});
        check(projectEntities(list(outside)).isEmpty(), "whole outside contraption survived");
        reject(() -> projectEntities(list(contraption("create:stationary_contraption", 1, 67, 4,
                new float[]{0, 0, 0, 6, 1, 1}))));
        reject(() -> projectEntities(list(contraption("create:carriage_contraption", 4, 67, 4,
                new float[]{0, 0, 0, 1, 1, 1}))));
        CompoundTag missing = entity("create:contraption", 4, 67, 4);
        missing.put("Contraption", new CompoundTag());
        reject(() -> projectEntities(list(missing)));
        reject(() -> projectEntities(list(contraption("create:stationary_contraption", 4, 67, 4,
                new float[]{0, 0, 0, Float.NaN, 1, 1}))));
        reject(() -> projectEntities(list(contraption("create:stationary_contraption", 4, 67, 4,
                new float[]{1, 0, 0, 0, 1, 1}))));
    }

    private static void malformedRetainedDataFailsClosed() {
        Fixture badStorage = fixture();
        badStorage.chunk.getList("sections", Tag.TAG_COMPOUND).getCompound(0)
                .getCompound("block_states").putLongArray("data", new long[1]);
        reject(badStorage::project);
        Fixture badIndex = fixture();
        CompoundTag states = badIndex.chunk.getList("sections", Tag.TAG_COMPOUND).getCompound(0).getCompound("block_states");
        SimpleBitStorage invalid = new SimpleBitStorage(4, 4096, states.getLongArray("data").clone());
        invalid.set(index(4, 66, 4), 15);
        states.putLongArray("data", invalid.getRaw());
        reject(badIndex::project);
        Fixture missingId = fixture();
        missingId.chunk.getList("block_entities", Tag.TAG_COMPOUND).getCompound(0).remove("id");
        reject(missingId::project);
        Fixture missingTickId = fixture();
        missingTickId.chunk.getList("block_ticks", Tag.TAG_COMPOUND).getCompound(0).remove("i");
        reject(missingTickId::project);
        Fixture missingPoiType = fixture();
        missingPoiType.poi.orElseThrow().getCompound("Sections").getCompound("4")
                .getList("Records", Tag.TAG_COMPOUND).getCompound(0).remove("type");
        reject(missingPoiType::project);
        Fixture badProperty = fixture();
        CompoundTag properties = new CompoundTag();
        properties.putInt("facing", 1);
        badProperty.chunk.getList("sections", Tag.TAG_COMPOUND).getCompound(0).getCompound("block_states")
                .getList("palette", Tag.TAG_COMPOUND).getCompound(1).put("Properties", properties);
        reject(badProperty::project);
        Fixture wrongChunk = fixture();
        wrongChunk.chunk.putInt("xPos", 1);
        reject(wrongChunk::project);
        Fixture wrongSectionY = fixture();
        wrongSectionY.chunk.getList("sections", Tag.TAG_COMPOUND).getCompound(0).putInt("Y", 260);
        reject(wrongSectionY::project);
        Fixture duplicate = fixture();
        ListTag sections = duplicate.chunk.getList("sections", Tag.TAG_COMPOUND);
        sections.add(sections.getCompound(0).copy());
        reject(duplicate::project);
        reject(() -> projectEntities(list(entity("test:ordinary", Double.NaN, 66, 4))));
        CompoundTag noPosition = new CompoundTag();
        noPosition.putString("id", "test:ordinary");
        reject(() -> projectEntities(list(noPosition)));
        CompoundTag wrongPositionType = entity("test:ordinary", 4, 66, 4);
        ListTag floats = new ListTag();
        floats.add(FloatTag.valueOf(4));
        floats.add(FloatTag.valueOf(66));
        floats.add(FloatTag.valueOf(4));
        wrongPositionType.put("Pos", floats);
        reject(() -> projectEntities(list(wrongPositionType)));
    }

    private static void positionEdgesAndLegacyEntityRecords() {
        ListTag positions = list(entity("test:low", 0, 64, 0),
                entity("test:high_inside", 8.999, 72.999, 8.999),
                entity("test:high_outside", 9, 72, 8));
        check(projectEntities(positions).size() == 2, "half-open entity boundary was rounded incorrectly");
        Fixture fixture = fixture();
        fixture.chunk.put("entities", list(entity("test:legacy", 4, 73, 4)));
        fixture.chunk.put("Entities", list(entity("test:legacy", 4, 80, 4)));
        var projected = fixture.project();
        check(projected.chunk().getList("entities", Tag.TAG_COMPOUND).isEmpty()
                        && projected.chunk().getList("Entities", Tag.TAG_COMPOUND).isEmpty(),
                "legacy entity records must not bypass projection");
        var emptyPoi = EvaluationRoomProjection.project(POS, fixture.chunk, new ListTag(), fixture.poi,
                new EvaluationRoomProjection.Bounds(32, 64, 32, 40, 72, 40));
        check(emptyPoi.poi().isEmpty(), "all-outside POI must not be published");
    }

    private static Fixture fixture() {
        int[] values = new int[4096];
        for (int y = 64; y <= 72; y++) {
            for (int z = 0; z <= 8; z++) {
                for (int x = 0; x <= 8; x++) {
                    if (x == 0 || x == 8 || y == 64 || y == 72 || z == 0 || z == 8) {
                        values[index(x, y, z)] = 1;
                    }
                }
            }
        }
        values[index(4, 66, 4)] = 2;
        values[index(4, 73, 4)] = 2;
        CompoundTag roomSection = section(4, new String[]{"minecraft:air", WALL, "minecraft:chest"}, values);
        roomSection.putByteArray("BlockLight", new byte[2048]);
        roomSection.putByteArray("SkyLight", new byte[2048]);
        CompoundTag source = chunk(POS, roomSection,
                section(5, new String[]{"minecraft:chest"}, new int[4096]));
        source.put("block_entities", list(chest(4, 66, 4, 7), chest(4, 73, 4, 64), chest(4, 80, 4, 64)));
        source.put("block_ticks", list(tick("minecraft:chest", 4, 66, 4), tick("minecraft:chest", 4, 73, 4)));
        source.put("fluid_ticks", list(tick("minecraft:water", 4, 66, 4), tick("minecraft:water", 4, 73, 4)));
        CompoundTag heightmaps = new CompoundTag();
        heightmaps.putLongArray("WORLD_SURFACE", new long[36]);
        source.put("Heightmaps", heightmaps);
        ListTag offsets = new ListTag();
        offsets.add(ShortTag.valueOf((short) index(4, 73, 4)));
        source.put("PostProcessing", list(offsets));
        source.put("UpgradeData", new CompoundTag());
        source.putBoolean("isLightOn", true);
        CompoundTag insideCart = entity("minecraft:chest_minecart", 4.5, 66, 4.5);
        insideCart.put("Items", items(3));
        CompoundTag outsideCart = entity("minecraft:chest_minecart", 4.5, 73.5, 4.5);
        outsideCart.put("Items", items(64));
        ListTag entities = list(insideCart, outsideCart, entity("ordinary_mod:pet", 5, 66, 5));
        CompoundTag poiSection = new CompoundTag();
        poiSection.putBoolean("Valid", true);
        poiSection.put("Records", list(poi(4, 66, 4), poi(4, 73, 4)));
        CompoundTag poiSections = new CompoundTag();
        poiSections.put("4", poiSection);
        CompoundTag poi = new CompoundTag();
        poi.putInt("DataVersion", 3955);
        poi.put("Sections", poiSections);
        return new Fixture(source, entities, Optional.of(poi));
    }

    private static CompoundTag chunk(ChunkPos pos, CompoundTag... sections) {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", 3955);
        chunk.putString("Status", "minecraft:full");
        chunk.putInt("xPos", pos.x);
        chunk.putInt("zPos", pos.z);
        chunk.put("sections", list(sections));
        return chunk;
    }

    private static CompoundTag section(int sectionY, String[] names, int[] values) {
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) sectionY);
        ListTag palette = new ListTag();
        for (String name : names) {
            CompoundTag state = new CompoundTag();
            state.putString("Name", name);
            palette.add(state);
        }
        CompoundTag states = new CompoundTag();
        states.put("palette", palette);
        if (names.length > 1) {
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(names.length - 1));
            int perLong = 64 / bits;
            long[] packed = new long[(4096 + perLong - 1) / perLong];
            for (int i = 0; i < values.length; i++) {
                packed[i / perLong] |= (long) values[i] << ((i % perLong) * bits);
            }
            states.putLongArray("data", packed);
        }
        section.put("block_states", states);
        return section;
    }

    private static String block(CompoundTag chunk, int x, int y, int z) {
        for (Tag entry : chunk.getList("sections", Tag.TAG_COMPOUND)) {
            CompoundTag section = (CompoundTag) entry;
            if (section.getByte("Y") != Math.floorDiv(y, 16)) {
                continue;
            }
            CompoundTag states = section.getCompound("block_states");
            ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
            int id = 0;
            if (palette.size() > 1) {
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                id = new SimpleBitStorage(bits, 4096, states.getLongArray("data")).get(index(x, y, z));
            }
            return palette.getCompound(id).getString("Name");
        }
        return "minecraft:air";
    }

    private static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    private static CompoundTag chest(int x, int y, int z, int count) {
        CompoundTag tag = position(x, y, z);
        tag.putString("id", "minecraft:chest");
        tag.put("Items", items(count));
        return tag;
    }

    private static ListTag items(int count) {
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:diamond");
        item.putInt("count", count);
        item.putByte("Slot", (byte) 0);
        return list(item);
    }

    private static int inventoryCount(CompoundTag chunk) {
        int count = 0;
        for (Tag entry : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
            for (Tag item : ((CompoundTag) entry).getList("Items", Tag.TAG_COMPOUND)) {
                count += ((CompoundTag) item).getInt("count");
            }
        }
        return count;
    }

    private static CompoundTag tick(String id, int x, int y, int z) {
        CompoundTag tag = position(x, y, z);
        tag.putString("i", id);
        tag.putInt("t", 1);
        tag.putInt("p", 0);
        return tag;
    }

    private static CompoundTag position(int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", x);
        tag.putInt("y", y);
        tag.putInt("z", z);
        return tag;
    }

    private static CompoundTag poi(int x, int y, int z) {
        CompoundTag poi = new CompoundTag();
        poi.putIntArray("pos", new int[]{x, y, z});
        poi.putString("type", "minecraft:home");
        poi.putInt("free_tickets", 1);
        return poi;
    }

    private static CompoundTag entity(String id, double x, double y, double z) {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", id);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        entity.put("Pos", pos);
        return entity;
    }

    private static CompoundTag contraption(String id, double x, double y, double z, float[] bounds) {
        CompoundTag entity = entity(id, x, y, z);
        CompoundTag contraption = new CompoundTag();
        ListTag box = new ListTag();
        for (float coordinate : bounds) {
            box.add(FloatTag.valueOf(coordinate));
        }
        contraption.put("BoundsFront", box);
        entity.put("Contraption", contraption);
        return entity;
    }

    private static ListTag projectEntities(ListTag entities) {
        return EvaluationRoomProjection.project(POS,
                chunk(POS, section(4, new String[]{"minecraft:air"}, new int[4096])),
                entities, Optional.empty(), ROOM).entities();
    }

    private static ListTag list(Tag... tags) {
        ListTag list = new ListTag();
        for (Tag tag : tags) {
            list.add(tag);
        }
        return list;
    }

    private static void reject(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("unsafe NBT was accepted");
    }

    private static void expectAssertion(Runnable operation) {
        try {
            operation.run();
        } catch (AssertionError expected) {
            return;
        }
        throw new AssertionError("unprojected control did not expose outside content");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Fixture(CompoundTag chunk, ListTag entities, Optional<CompoundTag> poi) {
        EvaluationRoomProjection.Projection project() {
            return EvaluationRoomProjection.project(POS, chunk, entities, poi, ROOM);
        }
    }
}
