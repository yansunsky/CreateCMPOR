package com.yansunsky.createcmpor.evaluation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Projects cold chunk records onto the exact wall-inclusive inventory-scan bounds. */
final class EvaluationRoomProjection {
    private static final int SECTION_SIZE = 4096;
    private static final double SCAN_EPSILON = 1.0E-5;
    private static final Set<String> CONTRAPTION_TYPES = Set.of(
            "create:contraption", "create:stationary_contraption",
            "create:controlled_contraption", "create:gantry_contraption");

    private EvaluationRoomProjection() {
    }

    /** Inclusive block coordinates; obtained with exactly the same rounding as EvaluationAudit. */
    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Bounds {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw invalid("empty or inverted room bounds");
            }
        }

        static Bounds from(AABB box) {
            Objects.requireNonNull(box, "box");
            return new Bounds(floorCoordinate(box.minX), floorCoordinate(box.minY),
                    floorCoordinate(box.minZ), floorCoordinate(box.maxX - SCAN_EPSILON),
                    floorCoordinate(box.maxY - SCAN_EPSILON), floorCoordinate(box.maxZ - SCAN_EPSILON));
        }

        boolean containsBlock(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        boolean containsPosition(double x, double y, double z) {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && x >= minX && x < maxX + 1.0 && y >= minY && y < maxY + 1.0
                    && z >= minZ && z < maxZ + 1.0;
        }
    }

    record Projection(CompoundTag chunk, ListTag entities, Optional<CompoundTag> poi) {
    }

    /** No world access, registry lookup or modification of any of the supplied records. */
    static Projection project(ChunkPos pos, CompoundTag source, ListTag entities,
                              Optional<CompoundTag> poi, Bounds bounds) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(entities, "entities");
        Objects.requireNonNull(poi, "poi");
        if (integer(source, "xPos") != pos.x || integer(source, "zPos") != pos.z) {
            throw invalid("chunk coordinates disagree with the manifest");
        }
        int baseX = blockOrigin(pos.x);
        int baseZ = blockOrigin(pos.z);
        CompoundTag chunk = source.copy();
        ListTag sections = compounds(chunk, "sections");
        if (sections.isEmpty()) {
            throw invalid("chunk has no sections");
        }
        Set<Integer> seenSections = new HashSet<>();
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            if (!section.contains("Y", Tag.TAG_BYTE)) {
                throw invalid("section Y is not the vanilla byte coordinate");
            }
            int sectionY = section.getByte("Y");
            if (!seenSections.add(sectionY)) {
                throw invalid("duplicate section Y: " + sectionY);
            }
            projectSection(section, baseX, blockOrigin(sectionY), baseZ, bounds);
            section.remove("BlockLight");
            section.remove("SkyLight");
        }
        retainPositions(chunk, "block_entities", pos, bounds, false);
        retainPositions(chunk, "block_ticks", pos, bounds, true);
        retainPositions(chunk, "fluid_ticks", pos, bounds, true);
        for (String key : new String[]{"entities", "Entities"}) {
            if (chunk.contains(key)) {
                chunk.put(key, projectEntities(compounds(chunk, key), bounds));
            }
        }
        // Vanilla recomputes absent heightmaps and lighting from the projected sections.
        // Pending upgrades/postprocessing must not reintroduce removed states or updates.
        for (String key : new String[]{"Heightmaps", "PostProcessing", "UpgradeData", "Lights",
                "CarvingMasks", "blending_data", "below_zero_retrogen", "structures"}) {
            chunk.remove(key);
        }
        chunk.putBoolean("isLightOn", false);
        return new Projection(chunk, projectEntities(entities, bounds), projectPoi(poi, pos, bounds));
    }

    private static void projectSection(CompoundTag section, int baseX, int baseY, int baseZ, Bounds bounds) {
        boolean intersects = baseX <= bounds.maxX && baseX + 15 >= bounds.minX
                && baseY <= bounds.maxY && baseY + 15 >= bounds.minY
                && baseZ <= bounds.maxZ && baseZ + 15 >= bounds.minZ;
        if (!intersects) {
            section.put("block_states", airStates());
            return;
        }
        if (!section.contains("block_states")) {
            section.put("block_states", airStates());
            return;
        }
        if (!section.contains("block_states", Tag.TAG_COMPOUND)) {
            throw invalid("section block_states is not a compound");
        }
        CompoundTag blockStates = section.getCompound("block_states");
        ListTag originalPalette = compounds(blockStates, "palette");
        if (originalPalette.isEmpty() || originalPalette.size() > SECTION_SIZE) {
            throw invalid("invalid block palette size: " + originalPalette.size());
        }
        SimpleBitStorage original = null;
        if (originalPalette.size() > 1) {
            if (!blockStates.contains("data", Tag.TAG_LONG_ARRAY)) {
                throw invalid("non-singleton block palette has no packed data");
            }
            try {
                // 1.21.1 PalettedContainer uses a local disk palette, even for global runtime palettes.
                // SimpleBitStorage checks ceil(4096 / floor(64 / bits)) longs and never crosses a long.
                original = new SimpleBitStorage(bits(originalPalette.size()), SECTION_SIZE,
                        blockStates.getLongArray("data").clone());
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Invalid projected chunk block storage", exception);
            }
        } else if (blockStates.contains("data")
                && (!blockStates.contains("data", Tag.TAG_LONG_ARRAY)
                || blockStates.getLongArray("data").length != 0)) {
            throw invalid("singleton palette has unexpected packed data");
        }
        Map<CompoundTag, Integer> ids = new LinkedHashMap<>();
        ListTag palette = new ListTag();
        int[] indices = new int[SECTION_SIZE];
        CompoundTag air = airState();
        for (int index = 0; index < SECTION_SIZE; index++) {
            int x = baseX + (index & 15);
            int z = baseZ + ((index >>> 4) & 15);
            int y = baseY + (index >>> 8);
            CompoundTag state = air;
            if (bounds.containsBlock(x, y, z)) {
                int oldId = original == null ? 0 : original.get(index);
                if (oldId >= originalPalette.size()) {
                    throw invalid("block palette index out of range: " + oldId);
                }
                state = originalPalette.getCompound(oldId);
            }
            Integer id = ids.get(state);
            if (id == null) {
                requireId(state, "Name");
                if (state.contains("Properties")) {
                    if (!state.contains("Properties", Tag.TAG_COMPOUND)) {
                        throw invalid("block state Properties is not a compound");
                    }
                    CompoundTag properties = state.getCompound("Properties");
                    for (String property : properties.getAllKeys()) {
                        if (!properties.contains(property, Tag.TAG_STRING)) {
                            throw invalid("block state property is not a string: " + property);
                        }
                    }
                }
                id = ids.size();
                ids.put(state, id);
                palette.add(state.copy());
            }
            indices[index] = id;
        }
        CompoundTag projected = new CompoundTag();
        projected.put("palette", palette);
        if (palette.size() > 1) {
            SimpleBitStorage storage = new SimpleBitStorage(bits(palette.size()), SECTION_SIZE, indices);
            projected.putLongArray("data", storage.getRaw().clone());
        }
        section.put("block_states", projected);
    }

    private static void retainPositions(CompoundTag chunk, String key, ChunkPos pos, Bounds bounds,
                                        boolean tick) {
        if (!chunk.contains(key)) {
            return;
        }
        ListTag output = new ListTag();
        for (Tag entry : compounds(chunk, key)) {
            CompoundTag tag = (CompoundTag) entry;
            int x = integer(tag, "x");
            int y = integer(tag, "y");
            int z = integer(tag, "z");
            // Drop by position before inspecting payloads: outside blocks must not affect eligibility.
            if (!bounds.containsBlock(x, y, z)) {
                continue;
            }
            requireChunk(pos, x, z);
            requireId(tag, tick ? "i" : "id");
            if (tick) {
                integer(tag, "t");
                integer(tag, "p");
            }
            output.add(tag.copy());
        }
        chunk.put(key, output);
    }

    private static ListTag projectEntities(ListTag entities, Bounds bounds) {
        requireCompoundElements(entities);
        ListTag output = new ListTag();
        for (Tag entry : entities) {
            CompoundTag entity = (CompoundTag) entry;
            if (insideTree(entity, bounds, 0)) {
                output.add(entity.copy());
            }
        }
        return output;
    }

    private static boolean insideTree(CompoundTag entity, Bounds bounds, int depth) {
        if (depth > 8) {
            throw invalid("entity passenger nesting exceeds eight levels");
        }
        double[] point = numbers(entity, "Pos", 3, Tag.TAG_DOUBLE);
        boolean inside = bounds.containsPosition(point[0], point[1], point[2]);
        String id = entity.getString("id");
        if (id.startsWith("create:") && (id.contains("contraption") || id.contains("carriage"))) {
            verifyContraption(entity, point, id, bounds, inside);
        }
        if (entity.contains("Passengers")) {
            for (Tag passenger : compounds(entity, "Passengers")) {
                if (insideTree((CompoundTag) passenger, bounds, depth + 1) != inside) {
                    throw invalid("entity passenger group crosses the room boundary");
                }
            }
        }
        return inside;
    }

    private static void verifyContraption(CompoundTag entity, double[] point, String id,
                                          Bounds bounds, boolean inside) {
        if (!CONTRAPTION_TYPES.contains(id) || !entity.contains("Contraption", Tag.TAG_COMPOUND)) {
            // Carriages have multiple assemblies/dimensional anchors; a Pos-only guess is unsafe.
            throw invalid("contraption geometry cannot be verified: " + id);
        }
        double[] local = numbers(entity.getCompound("Contraption"), "BoundsFront", 6, Tag.TAG_FLOAT);
        if (local[0] >= local[3] || local[1] >= local[4] || local[2] >= local[5]) {
            throw invalid("contraption bounds are empty or inverted");
        }
        // Create AbstractContraptionEntity rotates around local (.5,.5,.5). Enclose all rotations,
        // avoiding a live entity, interpolated orientation or incomplete contraption deserialization.
        double x = Math.max(Math.abs(local[0] - .5), Math.abs(local[3] - .5));
        double y = Math.max(Math.abs(local[1] - .5), Math.abs(local[4] - .5));
        double z = Math.max(Math.abs(local[2] - .5), Math.abs(local[5] - .5));
        double radius = Math.sqrt(x * x + y * y + z * z);
        // OrientedContraptionEntity.getAnchorVec subtracts (.5,0,.5); controlled/gantry do not.
        double anchorShift = "create:contraption".equals(id) ? 0 : .5;
        double centerX = point[0] + anchorShift;
        double centerY = point[1] + .5;
        double centerZ = point[2] + anchorShift;
        if (!Double.isFinite(radius) || !Double.isFinite(centerX + radius)
                || !Double.isFinite(centerY + radius) || !Double.isFinite(centerZ + radius)) {
            throw invalid("non-finite contraption geometry");
        }
        AABB box = new AABB(centerX - radius, centerY - radius, centerZ - radius,
                centerX + radius, centerY + radius, centerZ + radius);
        boolean contained = box.minX >= bounds.minX && box.maxX <= bounds.maxX + 1.0
                && box.minY >= bounds.minY && box.maxY <= bounds.maxY + 1.0
                && box.minZ >= bounds.minZ && box.maxZ <= bounds.maxZ + 1.0;
        boolean intersects = box.maxX > bounds.minX && box.minX < bounds.maxX + 1.0
                && box.maxY > bounds.minY && box.minY < bounds.maxY + 1.0
                && box.maxZ > bounds.minZ && box.minZ < bounds.maxZ + 1.0;
        if ((inside && !contained) || (!inside && intersects)) {
            throw invalid("contraption crosses the room boundary or its rotation cannot fit");
        }
    }

    private static Optional<CompoundTag> projectPoi(Optional<CompoundTag> source, ChunkPos pos,
                                                   Bounds bounds) {
        if (source.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag poi = source.get().copy();
        if (!poi.contains("Sections")) {
            return Optional.empty();
        }
        if (!poi.contains("Sections", Tag.TAG_COMPOUND)) {
            throw invalid("POI Sections is not a compound");
        }
        CompoundTag keptSections = new CompoundTag();
        CompoundTag sections = poi.getCompound("Sections");
        for (String key : sections.getAllKeys()) {
            if (!sections.contains(key, Tag.TAG_COMPOUND)) {
                throw invalid("POI section is not a compound");
            }
            CompoundTag section = sections.getCompound(key);
            ListTag records = new ListTag();
            for (Tag entry : compounds(section, "Records")) {
                CompoundTag record = (CompoundTag) entry;
                if (!record.contains("pos", Tag.TAG_INT_ARRAY) || record.getIntArray("pos").length != 3) {
                    throw invalid("POI record has no valid block position");
                }
                int[] p = record.getIntArray("pos");
                if (!bounds.containsBlock(p[0], p[1], p[2])) {
                    continue;
                }
                requireChunk(pos, p[0], p[2]);
                if (!Integer.toString(Math.floorDiv(p[1], 16)).equals(key)) {
                    throw invalid("POI record belongs to another section");
                }
                requireId(record, "type");
                records.add(record.copy());
            }
            if (!records.isEmpty()) {
                CompoundTag kept = section.copy();
                kept.put("Records", records);
                kept.putBoolean("Valid", false);
                keptSections.put(key, kept);
            }
        }
        if (keptSections.isEmpty()) {
            return Optional.empty();
        }
        poi.put("Sections", keptSections);
        return Optional.of(poi);
    }

    private static ListTag compounds(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return new ListTag();
        }
        if (!tag.contains(key, Tag.TAG_LIST)) {
            throw invalid(key + " is not a list");
        }
        ListTag list = (ListTag) tag.get(key);
        requireCompoundElements(list);
        return list;
    }

    private static void requireCompoundElements(ListTag list) {
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
            throw invalid("expected a compound list");
        }
    }

    private static double[] numbers(CompoundTag tag, String key, int size, int elementType) {
        if (!tag.contains(key, Tag.TAG_LIST)) {
            throw invalid(key + " is not a numeric list");
        }
        ListTag list = (ListTag) tag.get(key);
        if (list.size() != size || list.getElementType() != elementType) {
            throw invalid(key + " has an invalid length or numeric tag type");
        }
        double[] result = new double[size];
        for (int i = 0; i < size; i++) {
            if (!(list.get(i) instanceof NumericTag number) || !Double.isFinite(number.getAsDouble())) {
                throw invalid(key + " contains a non-finite or non-numeric coordinate");
            }
            result[i] = number.getAsDouble();
        }
        return result;
    }

    private static int integer(CompoundTag tag, String key) {
        if (!(tag.get(key) instanceof NumericTag number)) {
            throw invalid(key + " is not a numeric coordinate");
        }
        double value = number.getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid(key + " is not a finite integer");
        }
        return (int) value;
    }

    private static void requireId(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_STRING) || ResourceLocation.tryParse(tag.getString(key)) == null) {
            throw invalid("missing or invalid " + key);
        }
    }

    private static void requireChunk(ChunkPos pos, int x, int z) {
        if (Math.floorDiv(x, 16) != pos.x || Math.floorDiv(z, 16) != pos.z) {
            throw invalid("retained block record belongs to another chunk");
        }
    }

    private static int floorCoordinate(double value) {
        double floor = Math.floor(value);
        if (!Double.isFinite(floor) || floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) {
            throw invalid("room coordinate is outside the integer range");
        }
        return (int) floor;
    }

    private static int blockOrigin(int section) {
        long origin = (long) section * 16;
        if (origin < Integer.MIN_VALUE || origin > Integer.MAX_VALUE - 15L) {
            throw invalid("chunk or section coordinate overflows block coordinates");
        }
        return (int) origin;
    }

    private static int bits(int paletteSize) {
        return Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    private static CompoundTag airState() {
        CompoundTag air = new CompoundTag();
        air.putString("Name", "minecraft:air");
        return air;
    }

    private static CompoundTag airStates() {
        ListTag palette = new ListTag();
        palette.add(airState());
        CompoundTag states = new CompoundTag();
        states.put("palette", palette);
        return states;
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("Unsafe evaluation room projection: " + detail);
    }
}
