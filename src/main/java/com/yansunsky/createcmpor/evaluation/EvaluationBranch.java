package com.yansunsky.createcmpor.evaluation;

import java.util.UUID;

/** 并行评估的单分支事务。 */
final class EvaluationBranch {
    enum Phase {
        STAGED,
        PUBLISHING,
        PUBLISHED,
        EVALUATING,
        EVALUATED,
        CLEANED
    }

    private final int index;
    private final UUID branchId;
    private final EvaluationManifest manifest;
    private Phase phase = Phase.STAGED;
    private EvaluationVerdict.Result result;
    private boolean targetReady;
    private boolean ticketsRemoved;
    private boolean targetCleaned;

    EvaluationBranch(int index, UUID branchId, EvaluationManifest manifest) {
        this.index = index;
        this.branchId = branchId;
        this.manifest = manifest;
    }

    int index() {
        return index;
    }

    UUID branchId() {
        return branchId;
    }

    EvaluationManifest manifest() {
        return manifest;
    }

    Phase phase() {
        return phase;
    }

    void setPhase(Phase phase) {
        this.phase = phase;
    }

    boolean targetReady() {
        return targetReady;
    }

    void setTargetReady(boolean targetReady) {
        this.targetReady = targetReady;
    }

    EvaluationVerdict.Result result() {
        return result;
    }

    void setResult(EvaluationVerdict.Result result) {
        this.result = result;
        if (result != null) {
            this.phase = Phase.EVALUATED;
        }
    }

    boolean ticketsRemoved() {
        return ticketsRemoved;
    }

    void setTicketsRemoved(boolean ticketsRemoved) {
        this.ticketsRemoved = ticketsRemoved;
    }

    boolean targetCleaned() {
        return targetCleaned;
    }

    void setTargetCleaned(boolean targetCleaned) {
        this.targetCleaned = targetCleaned;
    }

    net.minecraft.nbt.CompoundTag save() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("index", index);
        tag.putUUID("branch_id", branchId);
        tag.put("manifest", manifest.save());
        tag.putString("phase", phase.name());
        return tag;
    }

    static EvaluationBranch load(net.minecraft.nbt.CompoundTag tag,
                                 net.minecraft.core.HolderLookup.Provider registries) {
        EvaluationManifest manifest = EvaluationManifest.load(tag.getCompound("manifest"), registries);
        EvaluationBranch branch = new EvaluationBranch(
                tag.getInt("index"), tag.getUUID("branch_id"), manifest);
        try {
            branch.phase = Phase.valueOf(tag.getString("phase"));
        } catch (IllegalArgumentException ignored) {
            branch.phase = Phase.STAGED;
        }
        return branch;
    }
}
