package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.UserAccountStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Offline real-Postgres E2E of the STR-181 Mind decision -> virtual F&O
 * order -> wallet / independent book reconciliation -> exit -> deduplication.
 *
 * Provider contract and V3 ticks are fixed, explicit test doubles, NOT
 * historical source evidence or an observed NSE session. This tests only
 * virtual money and does not connect to any broker order transport.
 */
class CustomerMindFnoPaperCycleLedgerIT extends AbstractPostgresIT {
    private static final Instant NOW=Instant.parse("2026-11-16T04:00:00Z");
    private static final Instant PREVIOUS_CLOSE=Instant.parse("2026-11-13T10:00:00Z");
    private static final LocalDate EXPIRY=LocalDate.of(2026,11,17);
    private static final String CONTRACT="NSE_FO|61001";
    private static final String UNDERLYING="NSE_INDEX|Nifty 50";
    private static final long INITIAL=10_000_000L; // virtual paise only

    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;
    @Autowired CustomerPaperWalletService wallets;
    @Autowired MarketSessionCalendar calendar;
    @Autowired PlatformTransactionManager txManager;

    private <T> T tx(java.util.function.Supplier<T> call) {
        return new TransactionTemplate(txManager).execute(status -> call.get());
    }

    private UUID fundedRoom() {
        UUID suffix=UUID.randomUUID();
        UUID owner=users.createUser("mind-fno-ledger-"+suffix,
            "Mind PAPER fixture","mind-fno-ledger-"+suffix+"@example.test",
            "test:customer").id();
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:id")
            .param("id",owner).update();
        UUID room=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO customer_trading_room
              (id,user_id,name,focus,planned_budget_inr,risk_preference,
               mode,market_scope,status)
            VALUES (:id,:owner,:name,'INTRADAY',100000,'BALANCED',
                    'PAPER','FNO','DRAFT')
            """).param("id",room).param("owner",owner)
            .param("name","Mind FNO ledger acceptance "+suffix).update();
        wallets.activate(owner,room);
        wallets.setPaused(owner,room,false);
        assertThat(wallets.get(owner,room).initialCashPaise()).isEqualTo(INITIAL);
        return room;
    }

    private static void quote(UpstoxFnoV3LiveFeedService live,
                              String bid,String ask) {
        when(live.eligiblePaperQuote(CONTRACT)).thenReturn(java.util.Optional.of(
            new UpstoxFnoV3LiveFeedService.PaperQuote(CONTRACT,
                new BigDecimal(bid),new BigDecimal(ask),
                NOW.minusSeconds(2),NOW.minusSeconds(1))));
    }

    @Test
    void realVirtualLedgerSupportsExactlyOneEntryExitAndNoSecondEntryForSameSignal() {
        UUID room=fundedRoom();
        UUID owner=jdbc.sql("SELECT user_id FROM customer_trading_room WHERE id=:room")
            .param("room",room).query(UUID.class).single();

        var source=mock(UpstoxAnalyticsSource.class);
        var feed=mock(UpstoxFnoV3LiveFeedService.class);
        when(source.enabled()).thenReturn(true);
        when(source.currentFnoOptionContracts(UNDERLYING,EXPIRY)).thenReturn(List.of(
            new UpstoxAnalyticsSource.FnoOptionContract(UNDERLYING,CONTRACT,
                EXPIRY,new BigDecimal("26000"),"CE",65,new BigDecimal(".05"),
                "NIFTY TEST LONG CE")));
        quote(feed,"99.00","100.00");

        var orderEngine=new CustomerPaperFnoOrderService(jdbc,source,feed,
            calendar,Clock.fixed(NOW,ZoneOffset.UTC),true,true);
        var recon=new CustomerPaperFnoReconciliationService(jdbc);
        var walletAudit=new CustomerPaperSharedWalletAuditService(jdbc);
        var research=mock(CustomerMindFnoResearchController.class);
        var capabilities=mock(CustomerPaperFnoCapabilityController.class);
        when(capabilities.internalCapability(owner,room)).thenReturn(
            new CustomerPaperFnoCapabilityController.Capability(
                "SOURCE_CHECK_REQUIRED",true,true,false,List.of()));

        var candidate=mock(CustomerMindFnoResearchController.FnoResearch.class);
        when(candidate.status()).thenReturn("RESEARCH_CANDIDATE");
        when(candidate.strategyId()).thenReturn("strategy.STR-181");
        when(candidate.signal()).thenReturn("BUY_CALL_RESEARCH");
        when(candidate.quoteSource()).thenReturn("UPSTOX_V3_EXCHANGE_TIMED");
        when(candidate.exchangeAt()).thenReturn(NOW.minusSeconds(2));
        when(candidate.observedAt()).thenReturn(NOW.minusSeconds(1));
        when(candidate.latestCompletedCloseAt()).thenReturn(PREVIOUS_CLOSE);
        when(candidate.ask()).thenReturn(new BigDecimal("100"));
        when(candidate.lotSize()).thenReturn(65);
        when(candidate.instrumentKey()).thenReturn(CONTRACT);
        when(candidate.expiry()).thenReturn(EXPIRY);
        when(research.inspectForOwnedPaperWorker()).thenReturn(candidate);

        var cycle=new CustomerMindFnoPaperCycleService(research,orderEngine,
            wallets,capabilities,recon,walletAudit,feed,jdbc,
            Clock.fixed(NOW,ZoneOffset.UTC));

        var entered=tx(()->cycle.runOptedIn(owner,room));
        assertThat(entered.status()).isEqualTo("FILLED_RECONCILED");
        assertThat(entered.action()).isEqualTo("ENTER");
        assertThat(entered.strategyId()).isEqualTo("strategy.STR-181");
        assertThat(entered.fillId()).isNotNull();
        assertThat(entered.liveMoney()).isFalse();
        assertThat(recon.inspect(owner,room).status()).isEqualTo("BOOKKEEPING_MATCHED");
        assertThat(walletAudit.inspect(owner,room).cashAndRealizedPnlReconciled()).isTrue();
        assertThat(orderEngine.orders(owner,room)).hasSize(1);
        assertThat(orderEngine.positions(owner,room)).hasSize(1);

        // The same completed-bar candidate cannot create a second position.
        var holding=tx(()->cycle.runOptedIn(owner,room));
        assertThat(holding.action()).isEqualTo("NONE");
        assertThat(holding.status()).isEqualTo("HOLD");
        assertThat(orderEngine.orders(owner,room)).hasSize(1);

        // A new fresh, exchange-time-attributed bid triggers a long-option
        // take-profit exit. Sell is virtual and uses the bid, not the ask.
        quote(feed,"120.00","121.00");
        var exited=tx(()->cycle.runOptedIn(owner,room));
        assertThat(exited.status()).isEqualTo("FILLED_RECONCILED");
        assertThat(exited.action()).isEqualTo("EXIT");
        assertThat(exited.realizedPnlDeltaPaise()).isPositive();
        assertThat(exited.liveMoney()).isFalse();
        assertThat(orderEngine.positions(owner,room)).isEmpty();
        assertThat(orderEngine.orders(owner,room)).hasSize(2);
        assertThat(recon.inspect(owner,room).completedRoundTrips()).isEqualTo(1);
        assertThat(walletAudit.inspect(owner,room).cashAndRealizedPnlReconciled()).isTrue();

        // Once closed, the old EMA crossover cannot open the same option again.
        var replay=tx(()->cycle.runOptedIn(owner,room));
        assertThat(replay.status()).isEqualTo("NO_TRADE");
        assertThat(replay.reasonCode()).isEqualTo("MIND_FNO_SIGNAL_ALREADY_TRADED");
        assertThat(replay.action()).isEqualTo("NONE");
        assertThat(orderEngine.orders(owner,room)).hasSize(2);
        assertThat(wallets.get(owner,room).realizedPnlPaise()).isPositive();
        verify(source,atLeastOnce()).currentFnoOptionContracts(UNDERLYING,EXPIRY);
    }
}
