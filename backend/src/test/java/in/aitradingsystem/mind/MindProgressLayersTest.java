package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.mind.MindCapabilityProgressService.EvidenceLink;
import in.aitradingsystem.mind.MindCapabilityProgressService.Item;
import in.aitradingsystem.mind.MindCapabilityProgressService.Layers;
import in.aitradingsystem.mind.MindCapabilityProgressService.Runtime;
import in.aitradingsystem.mind.MindCapabilityProgressService.Status;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each layer must come from recorded evidence. Catalog names and declared labels must not inflate it. */
class MindProgressLayersTest {

    private static Runtime noRuntime() {
        return new Runtime(null, null, null, null, null, null, null, null, 0);
    }

    private static Item item(String id, Status declared, boolean done, Instant lastVerified,
                             List<EvidenceLink> evidence) {
        return new Item(id, "SCREEN", id, "PRIMARY", null, "Claude", done ? Status.RUNTIME_VERIFIED : declared,
            declared, done, null, List.of(), List.of(), null, evidence, List.of(), null,
            lastVerified == null ? noRuntime() : new Runtime(lastVerified, null, null, "rev", null, null,
                null, null, 0));
    }

    private static final EvidenceLink SOURCE = new EvidenceLink("SOURCE", "lib/a.dart", null);
    private static final EvidenceLink TEST = new EvidenceLink("TEST", "test/a_test.dart", null);

    @Test
    void catalogOnlyItemsCountForNothing() {
        Layers layers = MindCapabilityProgressService.layers(List.of(
            item("catalog.only", Status.NOT_STARTED, false, null, List.of()),
            item("mock.only", Status.MOCK, false, null, List.of(TEST))));
        assertThat(layers.implemented().count()).isZero();
        assertThat(layers.ciTested().count()).isZero();
        assertThat(layers.deployed().count()).isZero();
        assertThat(layers.runtimeVerified().count()).isZero();
        assertThat(layers.runtimeVerified().total()).isEqualTo(2);
    }

    @Test
    void sourceEvidenceMakesAnItemImplementedNotTested() {
        Layers layers = MindCapabilityProgressService.layers(List.of(
            item("impl", Status.IMPLEMENTING, false, null, List.of(SOURCE))));
        assertThat(layers.implemented().count()).isEqualTo(1);
        assertThat(layers.ciTested().count()).isZero();
    }

    @Test
    void testEvidenceAloneDoesNotMakeAnItemCiTestedUnlessDeclaredTested() {
        Layers layers = MindCapabilityProgressService.layers(List.of(
            item("impl.tests", Status.IMPLEMENTING, false, null, List.of(SOURCE, TEST))));
        assertThat(layers.ciTested().count()).isZero();
        Layers tested = MindCapabilityProgressService.layers(List.of(
            item("tested", Status.TESTED, false, null, List.of(SOURCE, TEST))));
        assertThat(tested.ciTested().count()).isEqualTo(1);
    }

    @Test
    void deployedRequiresRecordedRuntimeEventEvenIfNotCurrentRevision() {
        Layers layers = MindCapabilityProgressService.layers(List.of(
            item("observed.old.revision", Status.TESTED, false, Instant.parse("2026-11-01T00:00:00Z"),
                List.of(SOURCE, TEST))));
        assertThat(layers.deployed().count()).isEqualTo(1);
        assertThat(layers.runtimeVerified().count()).isZero();
    }

    @Test
    void runtimeVerifiedUsesTheStrictDoneFlagOnly() {
        Layers layers = MindCapabilityProgressService.layers(List.of(
            item("verified", Status.TESTED, true, Instant.parse("2026-11-16T00:00:00Z"), List.of(SOURCE, TEST))));
        assertThat(layers.runtimeVerified().count()).isEqualTo(1);
        assertThat(layers.runtimeVerified().percent()).isEqualTo(100.0);
    }

    @Test
    void percentagesUseTheFullInventoryDenominator() {
        Layers layers = MindCapabilityProgressService.layers(List.of(
            item("a", Status.TESTED, false, null, List.of(SOURCE, TEST)),
            item("b", Status.NOT_STARTED, false, null, List.of()),
            item("c", Status.NOT_STARTED, false, null, List.of()),
            item("d", Status.NOT_STARTED, false, null, List.of())));
        assertThat(layers.implemented().total()).isEqualTo(4);
        assertThat(layers.implemented().percent()).isEqualTo(25.0);
    }
}
