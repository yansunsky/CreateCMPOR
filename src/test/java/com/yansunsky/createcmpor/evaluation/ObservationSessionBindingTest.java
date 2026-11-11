package com.yansunsky.createcmpor.evaluation;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Dependency-free checks run by the verifyObservationSessionBinding Gradle task. */
public final class ObservationSessionBindingTest {
    private ObservationSessionBindingTest() {
    }

    public static void main(String[] args) {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        long firstChunk = 12L;
        long secondChunk = 24L;
        var firstTarget = new ObservationSessionBinding.Candidate(
                first, "ROOM-A", "createcmpor:parallel_eval_0", Set.of(firstChunk), true);
        var secondTarget = new ObservationSessionBinding.Candidate(
                second, "ROOM-B", "createcmpor:parallel_eval_0", Set.of(secondChunk), true);
        var inactive = new ObservationSessionBinding.Candidate(
                UUID.randomUUID(), "ROOM-A", "createcmpor:parallel_eval_0", Set.of(firstChunk), false);

        check(ObservationSessionBinding.resolve(List.of(firstTarget, secondTarget),
                "createcmpor:parallel_eval_0", "ROOM-A", firstChunk).orElseThrow().equals(first),
                "a known room must disambiguate sessions sharing a lane dimension");
        check(ObservationSessionBinding.resolve(List.of(firstTarget, secondTarget),
                "createcmpor:parallel_eval_0", "", firstChunk).orElseThrow().equals(first),
                "a missing room code may bind by a unique manifest chunk");
        check(ObservationSessionBinding.resolve(List.of(firstTarget, secondTarget),
                "createcmpor:parallel_eval_0", "", 99L).isEmpty(),
                "an unknown chunk must fail closed");
        var overlapping = new ObservationSessionBinding.Candidate(
                second, "ROOM-B", "createcmpor:parallel_eval_0", Set.of(firstChunk), true);
        check(ObservationSessionBinding.resolve(List.of(firstTarget, overlapping),
                "createcmpor:parallel_eval_0", "", firstChunk).isEmpty(),
                "an ambiguous lane/chunk must fail closed rather than choose the first session");
        check(ObservationSessionBinding.resolve(List.of(firstTarget, secondTarget),
                "createcmpor:parallel_eval_0", "", firstChunk).orElseThrow().equals(first),
                "a different room at the same dimension must not win by iteration order");
        check(ObservationSessionBinding.resolve(List.of(firstTarget, inactive),
                "createcmpor:parallel_eval_0", "ROOM-A", firstChunk).orElseThrow().equals(first),
                "a stale or cleaning session must not be selected");

        Set<String> dimensions = Set.of("createcmpor:eval_world", "createcmpor:parallel_eval_0");
        check(ObservationSessionBinding.belongsToCleanup(first, first, "", "ROOM-A", "", dimensions),
                "a bound record follows its session id");
        check(!ObservationSessionBinding.belongsToCleanup(second, first, "ROOM-A", "ROOM-A",
                "createcmpor:eval_world", dimensions), "a different session id must never be evicted");
        check(ObservationSessionBinding.belongsToCleanup(null, first, "ROOM-A", "ROOM-A",
                "createcmpor:parallel_eval_0", dimensions), "a legacy record binds by room and dimension");
        check(!ObservationSessionBinding.belongsToCleanup(null, first, "ROOM-B", "ROOM-A",
                "createcmpor:parallel_eval_0", dimensions), "a different legacy room must not be evicted");
        check(!ObservationSessionBinding.belongsToCleanup(null, first, "ROOM-A", "ROOM-A",
                "minecraft:overworld", dimensions), "a record in another dimension must not be evicted");

        check(ObservationSessionBinding.withinPlayerTicketRange(0, 0, 4, -4, 4),
                "the Chebyshev view-distance border must be included");
        check(!ObservationSessionBinding.withinPlayerTicketRange(0, 0, 5, 0, 4),
                "chunks beyond the view distance must not be treated as ticketed");
        check(ObservationSessionBinding.withinPlayerTicketRange(0, 0, -3, 4, 4),
                "diagonal chunks inside the vanilla square ticket radius must be included");
        check(ObservationSessionBinding.mayHoldPlayerTickets(false, false),
                "non-spectator players can generate player tickets regardless of spectator gamerule");
        check(ObservationSessionBinding.mayHoldPlayerTickets(true, true),
                "spectators can generate player tickets when the gamerule is enabled");
        check(!ObservationSessionBinding.mayHoldPlayerTickets(true, false),
                "spectators without chunk generation enabled must not be ejected as ticket blockers");
        check(!ObservationSessionBinding.mayEvictCleanupOccupant(true, false, false),
                "a nearby live observer bound to another session must be preserved");
        check(ObservationSessionBinding.mayEvictCleanupOccupant(true, true, false),
                "an observer occupying an unowned cleanup target chunk may be moved out to release its ticket");
        check(!ObservationSessionBinding.mayEvictCleanupOccupant(true, true, true),
                "a cleanup target chunk owned by another session must be preserved");
        check(ObservationSessionBinding.mayEvictCleanupOccupant(false, true, false),
                "an unowned occupant of the cleanup footprint may be evicted");
        check(!ObservationSessionBinding.mayHoldPlayerTickets(false, false, false, false),
                "a pre-publish manifest without tickets or writes must not trigger ejection");
        check(ObservationSessionBinding.mayHoldPlayerTickets(false, true, false, false),
                "an added ticket must activate cleanup ejection");
        check(ObservationSessionBinding.mayHoldPlayerTickets(false, false, false, true),
                "a target write intent must keep cleanup responsibility active");
        check(ObservationSessionBinding.mayHoldPlayerTickets(true, false, false, false),
                "explicit target-conflict cleanup must be included even before normal publication");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
