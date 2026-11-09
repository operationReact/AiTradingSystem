package in.aitradingsystem.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterParser.InstrumentDraft;
import in.aitradingsystem.instrument.InstrumentMasterRepository;
import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.UserAccountStore;
import in.aitradingsystem.user.UserPreferenceModels.AppUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Runs the reconciliation SQL against PostgreSQL. The test rolls back, so catalogue changes do not leak. */
@Transactional
class CustomerWatchlistReconciliationIT extends AbstractPostgresIT {
    @Autowired
    private InstrumentMasterRepository catalogue;

    @Autowired
    private UserAccountStore users;

    @Autowired
    private CustomerWatchlistStore watchlist;

    private static InstrumentDraft equity(String symbol, String token) {
        return new InstrumentDraft("NSE_EQ", "NSE", token, symbol, symbol + " Ltd", "EQ", null,
            null, 1, new BigDecimal("0.05"));
    }

    private void publish(List<InstrumentDraft> drafts) {
        UUID run = UUID.randomUUID();
        catalogue.createImportRun(run, "test://watchlist-reconciliation");
        catalogue.publishCatalogue(run, drafts, 1L, drafts.size(), 0, 0, 0);
    }

    @Test
    void repointMovesRowAndCurrentLookupsResolveTheNewCatalogueVersion() {
        publish(List.of(equity("RELIANCE", "2885"), equity("TCS", "11536")));
        Instrument first = catalogue.findActiveInstrumentBySymbol("NSE", "RELIANCE").orElseThrow();
        AppUser owner = users.createUser("reconcile-sub-1", "Test", null, "test");
        watchlist.add(owner.id(), first);

        publish(List.of(equity("RELIANCE", "2885"), equity("TCS", "11536")));
        Instrument current = catalogue.findActiveInstrumentBySymbol("NSE", "RELIANCE").orElseThrow();
        assertThat(current.id()).isNotEqualTo(first.id());

        assertThat(catalogue.findActiveInstrumentsByIds(List.of(first.id()))).doesNotContainKey(first.id());
        assertThat(catalogue.findActiveInstrumentsByIds(List.of(current.id()))).containsKey(current.id());

        watchlist.repoint(owner.id(), first.id(), current.id());

        List<CustomerWatchlistStore.Item> items = watchlist.list(owner.id());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).instrumentId()).isEqualTo(current.id());
        assertThat(items.get(0).tradingSymbol()).isEqualTo("RELIANCE");
    }

    @Test
    void repointOntoAnAlreadyHeldIdRemovesTheStaleRowWithoutDuplicates() {
        publish(List.of(equity("RELIANCE", "2885")));
        Instrument first = catalogue.findActiveInstrumentBySymbol("NSE", "RELIANCE").orElseThrow();
        AppUser owner = users.createUser("reconcile-sub-2", "Test", null, "test");
        watchlist.add(owner.id(), first);

        publish(List.of(equity("RELIANCE", "2885")));
        Instrument current = catalogue.findActiveInstrumentBySymbol("NSE", "RELIANCE").orElseThrow();
        watchlist.add(owner.id(), current);

        watchlist.repoint(owner.id(), first.id(), current.id());

        List<CustomerWatchlistStore.Item> items = watchlist.list(owner.id());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).instrumentId()).isEqualTo(current.id());
    }

    @Test
    void unknownOrAmbiguousSymbolsResolveToEmpty() {
        publish(List.of(equity("RELIANCE", "2885")));
        Optional<Instrument> missing = catalogue.findActiveInstrumentBySymbol("NSE", "DELISTEDCO");
        assertThat(missing).isEmpty();
        assertThat(catalogue.findActiveInstrumentsByIds(List.of())).isEmpty();
    }
}
