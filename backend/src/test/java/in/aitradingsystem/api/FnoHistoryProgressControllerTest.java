package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class FnoHistoryProgressControllerTest {
    @TempDir Path directory;

    private final ObjectMapper mapper = new ObjectMapper();

    private FnoHistoryProgressController controller(String filename) {
        return new FnoHistoryProgressController(
            new MockEnvironment().withProperty("FNO_HISTORY_PROGRESS_SNAPSHOT_PATH", filename),
            mapper);
    }

    private java.util.Map<String,Object> sample(String instant, int total,
                                                   int done, double percent) {
        var data = new LinkedHashMap<String,Object>();
        data.put("schema","minttick.fno.history.progress.v1");
        data.put("source","upstox-expired-nse-fo");
        data.put("planScope","CURRENT_JOURNAL_BATCHES_ONLY");
        data.put("state","PREPARATION_ONLY");
        data.put("retentionRightsVerified",false);
        data.put("storageActive",false);
        data.put("generatedAt",instant);
        data.put("totalBatches",total);
        data.put("completedBatches",done);
        data.put("completionPercent",percent);
        data.put("rowsWritten",5);
        data.put("lastCompletedAt",instant);
        data.put("counts",java.util.Map.of(
            "PENDING",total-done,"ACTIVE",0,"RETRY",0,"DONE",done,
            "EMPTY",0,"FAILED",0,"BLOCKED",0));
        return data;
    }

    @Test void noConfiguredSnapshotDoesNotClaimZeroPercent() {
        var progress = new FnoHistoryProgressController(
            new MockEnvironment(),mapper).progress();
        assertEquals("NOT_CONFIGURED",progress.state());
        assertNull(progress.completionPercent());
        assertNull(progress.totalBatches());
        assertFalse(progress.storageActive());
        assertFalse(progress.retentionRightsVerified());
    }

    @Test void missingFileReturnsUnavailableNotInventedCompletion() {
        var result = controller(directory.resolve("missing.json").toString()).progress();
        assertEquals("UNAVAILABLE",result.state());
        assertNull(result.completionPercent());
    }

    @Test void authenticCurrentSnapshotShowsPlannedBatchProgressOnly() throws Exception {
        Path file=directory.resolve("status.json");
        Files.write(file,mapper.writeValueAsBytes(
            sample(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),4,2,50.0)));
        var result=controller(file.toString()).progress();
        assertEquals("PREPARATION_ONLY",result.state());
        assertEquals("CHECKPOINT_METADATA_ONLY",result.evidence());
        assertEquals(4,result.totalBatches());
        assertEquals(2,result.completedBatches());
        assertEquals(50.0,result.completionPercent());
        assertEquals(5L,result.rowsWritten());
        assertEquals(2,result.counts().get("PENDING"));
        assertFalse(result.storageActive());
        assertFalse(result.retentionRightsVerified());
    }

    @Test void oldSnapshotIsExplicitlyStale() throws Exception {
        Path file=directory.resolve("stale.json");
        Files.write(file,mapper.writeValueAsBytes(
            sample(Instant.now().minus(20,ChronoUnit.MINUTES).toString(),4,2,50.0)));
        assertEquals("STALE",controller(file.toString()).progress().state());
    }

    @Test void inconsistentOrForgedPercentageIsRejected() throws Exception {
        Path file=directory.resolve("bad.json");
        Files.write(file,mapper.writeValueAsBytes(
            sample(Instant.now().toString(),4,2,99.0)));
        var value=controller(file.toString()).progress();
        assertEquals("UNAVAILABLE",value.state());
        assertNull(value.completionPercent());
    }

    @Test void rejectedRightsAndUnknownFieldsNeverLeak() throws Exception {
        Path file=directory.resolve("unauthorized.json");
        var data=sample(Instant.now().toString(),1,0,0.0);
        data.put("retentionRightsVerified",true);
        data.put("bearerToken","NEVER-RETURN-THIS");
        Files.write(file,mapper.writeValueAsBytes(data));
        var result=controller(file.toString()).progress();
        assertEquals("UNAVAILABLE",result.state());
        assertFalse(result.retentionRightsVerified());
        assertFalse(result.storageActive());
        assertFalse(result.toString().contains("NEVER-RETURN-THIS"));
    }

    @Test void unsafeSymlinkIsNotFollowed() throws Exception {
        Path real=directory.resolve("private.json");
        Files.writeString(real,"SECRET_NOT_A_SNAPSHOT");
        Path link=directory.resolve("link.json");
        Files.createSymbolicLink(link,real.getFileName());
        var result=controller(link.toString()).progress();
        assertEquals("UNAVAILABLE",result.state());
    }
}
