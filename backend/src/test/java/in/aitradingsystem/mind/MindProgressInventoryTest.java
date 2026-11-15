package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import in.aitradingsystem.mind.MindCapabilityProgressService.InventoryPage;
import in.aitradingsystem.mind.MindCapabilityProgressService.Item;
import in.aitradingsystem.mind.MindCapabilityProgressService.Manifest;
import in.aitradingsystem.mind.MindCapabilityProgressService.ManifestItem;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Regression guard: progress responses must stay far below the 131,072-byte account-edge cap. */
class MindProgressInventoryTest {
    static final int EDGE_CAP = 131_072;
    private static final Instant NOW = Instant.parse("2026-11-16T04:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private static MindCapabilityProgressService realService() {
        return new MindCapabilityProgressService(null, Clock.fixed(NOW, ZoneOffset.UTC), "f11c2a7abc",
            MindCapabilityProgressService.loadManifest());
    }

    private static int bytes(Object value) throws Exception {
        return JSON.writeValueAsBytes(value).length;
    }

    @Test
    void summaryIsSmallAndReportsFullInventoryTotals() throws Exception {
        var service = realService();
        var summary = service.summary(null, Map.of(), NOW);

        assertThat(bytes(summary)).isLessThan(16_000);
        assertThat(summary.overall().total()).isEqualTo(313);
        assertThat(summary.overall().done() + summary.overall().pending()).isEqualTo(313);
        double expected = Math.round(summary.overall().done() * 1000.0 / 313) / 10.0;
        assertThat(summary.overall().percentDone()).isEqualTo(expected);
        assertThat(summary.summaries()).hasSize(summary.kinds().size());
    }

    @Test
    void pagesCoverTheWholeInventoryInManifestOrderWithoutLossOrDuplicates() throws Exception {
        var service = realService();
        List<String> manifestOrder = MindCapabilityProgressService.loadManifest().items().stream()
            .map(ManifestItem::id).toList();
        List<String> paged = new ArrayList<>();
        int offset = 0;
        Integer next;
        do {
            InventoryPage page = service.inventoryPage(null, Map.of(), NOW, null, null, offset, 50);
            assertThat(bytes(page)).isLessThan(EDGE_CAP);
            page.items().forEach(i -> paged.add(i.id()));
            next = page.nextOffset();
            offset = next == null ? offset : next;
        } while (next != null);

        assertThat(paged).containsExactlyElementsOf(manifestOrder);
        assertThat(Set.copyOf(paged)).hasSize(313);
    }

    @Test
    void kindAndStatusFiltersAreServerSideAndDeterministic() {
        var service = realService();
        InventoryPage screens = service.inventoryPage(null, Map.of(), NOW, "SCREEN", null, 0, 50);
        assertThat(screens.total()).isEqualTo(43);
        assertThat(screens.items()).allMatch(i -> i.kind().equals("SCREEN"));
        InventoryPage again = service.inventoryPage(null, Map.of(), NOW, "SCREEN", null, 0, 50);
        assertThat(again.items().stream().map(Item::id)).containsExactlyElementsOf(
            screens.items().stream().map(Item::id).toList());
    }

    @Test
    void percentageIsComputedFromTheFullInventoryNotFromAPage() {
        var service = realService();
        var summary = service.summary(null, Map.of(), NOW);
        InventoryPage onePage = service.inventoryPage(null, Map.of(), NOW, null, null, 0, 1);
        assertThat(onePage.items()).hasSize(1);
        assertThat(onePage.total()).isEqualTo(313);
        assertThat(summary.overall().total()).isEqualTo(313);
    }

    @Test
    void invalidRequestsAreRejected() {
        var service = realService();
        assertThatThrownBy(() -> service.inventoryPage(null, Map.of(), NOW, "NOT_A_KIND", null, 0, 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.inventoryPage(null, Map.of(), NOW, null, "NOT_A_STATUS", 0, 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.inventoryPage(null, Map.of(), NOW, null, null, -1, 10))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.inventoryPage(null, Map.of(), NOW, null, null, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.inventoryPage(null, Map.of(), NOW, null, null, 0, 51))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void largeItemsAreCutByTheByteBudgetAndNeverExceedTheEdgeCap() throws Exception {
        String heavy = "x".repeat(6_000);
        List<ManifestItem> items = new ArrayList<>();
        for (int i = 0; i < 60; i++) items.add(item("screen.heavy-" + i, "SCREEN", heavy));
        var service = synthetic(items);

        InventoryPage page = service.inventoryPage(null, Map.of(), NOW, null, null, 0, 50);

        assertThat(page.byteBudgetApplied()).isTrue();
        assertThat(page.items().size()).isLessThan(50);
        assertThat(page.nextOffset()).isEqualTo(page.items().size());
        assertThat(bytes(page)).isLessThan(EDGE_CAP);
    }

    @Test
    void summaryAndPagesStayBoundedAsTheInventoryGrows() throws Exception {
        List<ManifestItem> items = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) items.add(item("strategy.s-" + i, "STRATEGY", "notes"));
        var service = synthetic(items);

        var summary = service.summary(null, Map.of(), NOW);
        InventoryPage page = service.inventoryPage(null, Map.of(), NOW, null, null, 0, 50);

        assertThat(summary.overall().total()).isEqualTo(5_000);
        assertThat(bytes(summary)).isLessThan(16_000);
        assertThat(bytes(page)).isLessThan(EDGE_CAP);
        assertThat(page.nextOffset()).isNotNull();
    }

    private static MindCapabilityProgressService synthetic(List<ManifestItem> items) {
        return new MindCapabilityProgressService(null, Clock.fixed(NOW, ZoneOffset.UTC), "f11c2a7abc",
            new Manifest("v-synthetic", "d", List.of(), Map.of(), items));
    }

    private static ManifestItem item(String id, String kind, String note) {
        return new ManifestItem(id, kind, id, "G", null, "Claude", "MOCK", null, List.of(), List.of(),
            null, List.of(), List.of(), note);
    }
}
