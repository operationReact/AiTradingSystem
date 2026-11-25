package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.mind.MindCapabilityProgressService.EventType;
import in.aitradingsystem.mind.MindCapabilityProgressService.Status;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

/** Real Postgres proof that progress evidence is durable, idempotent and append-only. */
class MindCapabilityProgressServiceIT extends AbstractPostgresIT {
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final String REV = "0123abcd";

    @Autowired JdbcClient jdbc;
    @Autowired MindCapabilityProgressService springService;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;

    @Test
    void anonymousCallersCannotReadOrAnnotateProgress() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/customer/mind/v1/progress"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/customer/mind/v1/progress/items/screen.coverage/notes")
                .header("Idempotency-Key", "anon-key-0001").contentType("application/json")
                .content("{\"type\":\"NOTE\",\"text\":\"x\"}"))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    void springContextLoadsAuditedManifestWithUnknownRevisionByDefault() {
        var progress = springService.inspect(null);
        assertThat(progress.contractVersion()).isEqualTo("mind.progress.v1");
        assertThat(progress.items()).hasSize(progress.summaries().stream()
            .mapToInt(MindCapabilityProgressService.KindSummary::denominator).sum());
        assertThat(progress.revisionKnown()).isFalse();
        assertThat(progress.items()).noneMatch(MindCapabilityProgressService.Item::done);
    }

    @Test
    void runtimeEvidenceSurvivesNewServiceInstanceAndIsIdempotent() {
        String key = "it-runtime-" + UUID.randomUUID();
        var first = service(REV).recordRuntime("engine.data-gate", EventType.RUNTIME_VERIFIED,
            "candles-v1", "paper-cycle:1", "gate passed", key, NOW.minusSeconds(30));
        var replay = service(REV).recordRuntime("engine.data-gate", EventType.RUNTIME_VERIFIED,
            "candles-v1", "paper-cycle:1", "gate passed", key, NOW.minusSeconds(30));
        assertThat(replay.id()).isEqualTo(first.id());

        // "Restart": a brand-new service instance reads the persisted history.
        var restarted = service(REV).detail(null, "engine.data-gate");
        assertThat(restarted.item().status()).isEqualTo(Status.RUNTIME_VERIFIED);
        assertThat(restarted.history()).extracting(MindCapabilityProgressService.Event::id).contains(first.id());

        assertThatThrownBy(() -> service(REV).recordRuntime("engine.risk-gates",
            EventType.RUNTIME_FAILED, null, null, null, key, NOW))
            .isInstanceOf(ResponseStatusException.class);

        assertThatThrownBy(() -> jdbc.sql("UPDATE mind_capability_event SET detail='x' WHERE id=:id")
            .param("id", first.id()).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM mind_capability_event WHERE id=:id")
            .param("id", first.id()).update()).hasMessageContaining("append-only");
    }

    @Test
    void customerNotesAreScopedAndCannotForgeRuntimeEvidence() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var svc = service(REV);
        var note = svc.addNote(owner, "screen.coverage", EventType.RECHECK_REQUESTED, "recheck please",
            "recheck-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(note.actor()).isEqualTo("CUSTOMER:" + owner);
        assertThat(svc.detail(owner, "screen.coverage").history()).extracting(MindCapabilityProgressService.Event::id)
            .contains(note.id());
        assertThat(svc.detail(other, "screen.coverage").history()).extracting(MindCapabilityProgressService.Event::id)
            .doesNotContain(note.id());
        assertThat(svc.detail(owner, "screen.coverage").item().status()).isEqualTo(Status.IMPLEMENTING);

        assertThatThrownBy(() -> svc.addNote(owner, "screen.coverage", EventType.RUNTIME_VERIFIED, "x", "forged-key-1"))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO mind_capability_event
                  (id,capability_id,event_type,backend_revision,actor,idempotency_key,observed_at)
                VALUES (:id,'screen.coverage','RUNTIME_VERIFIED','x','CUSTOMER:x',:key,now())
                """).param("id", UUID.randomUUID()).param("key", "forged-" + UUID.randomUUID()).update())
            .hasMessageContaining("ck_mind_capability_runtime_actor");
        assertThatThrownBy(() -> svc.addNote(owner, "screen.does-not-exist", EventType.NOTE, "x", "missing-key-1"))
            .isInstanceOf(ResponseStatusException.class);
    }

    private MindCapabilityProgressService service(String revision) {
        return new MindCapabilityProgressService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), revision,
            MindCapabilityProgressService.loadManifest());
    }
}
