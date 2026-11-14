package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.mind.MindCapabilityProgressService.Event;
import in.aitradingsystem.mind.MindCapabilityProgressService.EventType;
import in.aitradingsystem.mind.MindCapabilityProgressService.Manifest;
import in.aitradingsystem.mind.MindCapabilityProgressService.ManifestItem;
import in.aitradingsystem.mind.MindCapabilityProgressService.Status;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MindCapabilityProgressServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final String REV = "f11c2a7abc";

    @Test
    void auditedManifestHasFixedDenominatorsAndNoRuntimeClaims() {
        Manifest manifest = MindCapabilityProgressService.loadManifest();
        Map<String, Long> kinds = manifest.items().stream()
            .collect(Collectors.groupingBy(ManifestItem::kind, Collectors.counting()));
        assertThat(kinds.get("SCREEN")).isEqualTo(43L);
        assertThat(kinds.get("STRATEGY")).isEqualTo(232L);
        assertThat(kinds.get("MODEL")).isEqualTo(18L);
        assertThat(manifest.items().stream().filter(i -> "STRATEGY".equals(i.kind()))
            .map(ManifestItem::group).collect(Collectors.toSet())).hasSize(20);
        assertThat(manifest.items()).noneMatch(i -> Set.of("RUNTIME_VERIFIED", "DEPLOYED", "FAILED")
            .contains(i.status()));
        // Exactly the audited MOCK screens (Settings became IMPLEMENTING in the Settings PR).
        assertThat(manifest.items().stream().filter(i -> "SCREEN".equals(i.kind())
            && "MOCK".equals(i.status())).count()).isEqualTo(4);
        assertThat(manifest.items()).filteredOn(i -> "SCREEN".equals(i.kind()))
            .allMatch(i -> Set.of("MOCK", "IMPLEMENTING", "NOT_STARTED", "DATA_BLOCKED").contains(i.status()));
        // A TESTED strategy must carry source and test evidence.
        manifest.items().stream().filter(i -> "STRATEGY".equals(i.kind()) && "TESTED".equals(i.status()))
            .forEach(i -> {
                assertThat(i.implementation()).as(i.id()).isNotNull();
                assertThat(i.evidence()).as(i.id()).anyMatch(e -> "TEST".equals(e.kind()));
            });
        assertThat(manifest.items()).filteredOn(i -> "live.broker-gated".equals(i.id()))
            .singleElement().extracting(ManifestItem::blockerCode).isEqualTo("LIVE_NOT_AUTHORIZED");
    }

    @Test
    void manifestCannotDeclareDoneOrMismatchDenominators() {
        ManifestItem verified = item("engine.x", "ENGINE", "RUNTIME_VERIFIED");
        assertThatThrownBy(() -> MindCapabilityProgressService.validate(
            new Manifest("v", "d", List.of(), Map.of(), List.of(verified))))
            .hasMessageContaining("runtime status");
        assertThatThrownBy(() -> MindCapabilityProgressService.validate(
            new Manifest("v", "d", List.of(), Map.of("SCREEN", 43),
                List.of(item("screen.a", "SCREEN", "MOCK")))))
            .hasMessageContaining("Denominator");
    }

    @Test
    void mockAndNotStartedNeverBecomeDoneEvenWithRuntimeEvidence() {
        var service = service(REV);
        for (String declared : List.of("MOCK", "NOT_STARTED", "IMPLEMENTING", "DATA_BLOCKED", "RIGHTS_BLOCKED")) {
            var item = service.evaluate(item("screen.x", "SCREEN", declared),
                List.of(runtime(EventType.RUNTIME_VERIFIED, REV, NOW.minusSeconds(5))), null, NOW);
            assertThat(item.status()).as(declared).isEqualTo(Status.valueOf(declared));
            assertThat(item.done()).isFalse();
        }
    }

    @Test
    void testedBecomesDoneOnlyOnCurrentKnownRevisionWithinTtl() {
        var declared = item("engine.paper-submit", "ENGINE", "TESTED");
        var fresh = runtime(EventType.RUNTIME_VERIFIED, REV, NOW.minusSeconds(60));

        var ok = service(REV).evaluate(declared, List.of(fresh), null, NOW);
        assertThat(ok.status()).isEqualTo(Status.RUNTIME_VERIFIED);
        assertThat(ok.done()).isTrue();
        assertThat(ok.runtime().verifiedRevision()).isEqualTo(REV);

        var unknown = service("unknown").evaluate(declared,
            List.of(runtime(EventType.RUNTIME_VERIFIED, "unknown", NOW.minusSeconds(60))), null, NOW);
        assertThat(unknown.status()).isEqualTo(Status.TESTED);
        assertThat(unknown.blockerCode()).isEqualTo("BACKEND_REVISION_UNKNOWN");

        var oldRevision = service("abcdef0123").evaluate(declared, List.of(fresh), null, NOW);
        assertThat(oldRevision.status()).isEqualTo(Status.TESTED);
        assertThat(oldRevision.blockerCode()).isEqualTo("REVERIFY_ON_CURRENT_REVISION");

        var expired = service(REV).evaluate(declared, List.of(runtime(EventType.RUNTIME_VERIFIED, REV,
            NOW.minus(MindCapabilityProgressService.VERIFICATION_TTL).minusSeconds(1))), null, NOW);
        assertThat(expired.status()).isEqualTo(Status.TESTED);
        assertThat(expired.blockerCode()).isEqualTo("VERIFICATION_EXPIRED");
    }

    @Test
    void laterFailureOrDataBlockRevertsVerifiedAndKeepsHistory() {
        var declared = item("engine.risk-gates", "ENGINE", "TESTED");
        var verified = runtime(EventType.RUNTIME_VERIFIED, REV, NOW.minusSeconds(600));
        var failed = runtime(EventType.RUNTIME_FAILED, REV, NOW.minusSeconds(60));
        var item = service(REV).evaluate(declared, List.of(failed, verified), null, NOW);
        assertThat(item.status()).isEqualTo(Status.FAILED);
        assertThat(item.done()).isFalse();
        assertThat(item.runtime().lastVerifiedAt()).isEqualTo(verified.observedAt());
        assertThat(item.runtime().lastFailedAt()).isEqualTo(failed.observedAt());

        var blocked = runtime(EventType.DATA_BLOCKED, REV, NOW.minusSeconds(30));
        assertThat(service(REV).evaluate(declared, List.of(blocked, verified), null, NOW).status())
            .isEqualTo(Status.DATA_BLOCKED);
    }

    @Test
    void ownerNotesNeverChangeStatusAndAreViewerScoped() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var declared = item("strategy.STR-001", "STRATEGY", "TESTED");
        var note = new Event(UUID.randomUUID(), declared.id(), EventType.RECHECK_REQUESTED, REV, null, null,
            "please recheck", MindCapabilityProgressService.customerActor(owner), NOW, NOW);
        var mine = service(REV).evaluate(declared, List.of(note), owner, NOW);
        assertThat(mine.status()).isEqualTo(Status.TESTED);
        assertThat(mine.runtime().recheckRequestedAt()).isEqualTo(NOW);
        assertThat(mine.runtime().ownNotes()).isEqualTo(1);
        var theirs = service(REV).evaluate(declared, List.of(note), other, NOW);
        assertThat(theirs.runtime().recheckRequestedAt()).isNull();
        assertThat(theirs.runtime().ownNotes()).isZero();
    }

    @Test
    void paperStageIsFirstUnverifiedLinkAndSummariesUseFixedDenominator() {
        var service = service(REV);
        var manifest = MindCapabilityProgressService.loadManifest();
        Map<String, MindCapabilityProgressService.Item> items = new java.util.LinkedHashMap<>();
        for (ManifestItem m : manifest.items()) {
            List<Event> events = Set.of("engine.data-gate", "engine.strategy-eval").contains(m.id())
                ? List.of(runtime(EventType.RUNTIME_VERIFIED, REV, NOW.minusSeconds(10))) : List.of();
            items.put(m.id(), service.evaluate(m, events, null, NOW));
        }
        var stage = MindCapabilityProgressService.paperStage(items);
        assertThat(stage.capabilityId()).isEqualTo("engine.risk-gates");
        var summaries = MindCapabilityProgressService.summaries(List.copyOf(items.values()));
        var screens = summaries.stream().filter(s -> "SCREEN".equals(s.kind())).findFirst().orElseThrow();
        assertThat(screens.denominator()).isEqualTo(43);
        assertThat(screens.done()).isZero();
        var engine = summaries.stream().filter(s -> "ENGINE".equals(s.kind())).findFirst().orElseThrow();
        assertThat(engine.done()).isEqualTo(2);
        Set<String> ids = new HashSet<>(items.keySet());
        assertThat(ids).containsAll(MindCapabilityProgressService.PAPER_CHAIN);
    }

    private static MindCapabilityProgressService service(String revision) {
        return new MindCapabilityProgressService(null, Clock.fixed(NOW, ZoneOffset.UTC), revision,
            MindCapabilityProgressService.loadManifest());
    }

    private static ManifestItem item(String id, String kind, String status) {
        return new ManifestItem(id, kind, id, "G", null, "Claude", status, null, List.of(), List.of(),
            null, List.of(), List.of(), null);
    }

    private static Event runtime(EventType type, String revision, Instant observed) {
        return new Event(UUID.randomUUID(), "x", type, revision, "dataset-v1", "ref", null, "SYSTEM",
            observed, observed.plus(Duration.ofSeconds(1)));
    }
}
