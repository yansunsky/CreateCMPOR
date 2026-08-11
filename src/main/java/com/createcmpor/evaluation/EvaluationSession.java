package com.createcmpor.evaluation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * 单次平行房间评估会话。
 *
 * <p>Phase 3 只建立入口、锁定与复制闭环；采样、固化和清理将在后续阶段补齐。</p>
 */
public class EvaluationSession {

    public enum State {
        /** 已创建会话，等待执行。 */
        IDLE,
        /** 正在锁定原机器。 */
        LOCKING,
        /** 正在复制源房间到评估房间。 */
        CLONING,
        /** Phase 3 复制完成，等待后续阶段接入采样。 */
        DONE,
        /** 执行失败，原机器应解除锁定。 */
        FAILED
    }

    private final UUID id;
    private final UUID owner;
    private final GlobalPos machinePos;
    private final String sourceRoomCode;
    private State state;
    private String evaluationRoomCode;
    private Component failureReason;

    public EvaluationSession(UUID id, UUID owner, GlobalPos machinePos, String sourceRoomCode) {
        this.id = id;
        this.owner = owner;
        this.machinePos = machinePos;
        this.sourceRoomCode = sourceRoomCode;
        this.state = State.IDLE;
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public GlobalPos machinePos() {
        return machinePos;
    }

    public ResourceKey<Level> machineDimension() {
        return machinePos.dimension();
    }

    public BlockPos machineBlockPos() {
        return machinePos.pos();
    }

    public String sourceRoomCode() {
        return sourceRoomCode;
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public String evaluationRoomCode() {
        return evaluationRoomCode;
    }

    public void setEvaluationRoomCode(String evaluationRoomCode) {
        this.evaluationRoomCode = evaluationRoomCode;
    }

    public Component failureReason() {
        return failureReason;
    }

    public void fail(Component reason) {
        this.state = State.FAILED;
        this.failureReason = reason;
    }

    public boolean isActive() {
        // Phase 3 的 DONE 表示“复制完成，等待后续采样/固化阶段接管”，原机器仍需保持锁定。
        return state != State.FAILED;
    }
}
