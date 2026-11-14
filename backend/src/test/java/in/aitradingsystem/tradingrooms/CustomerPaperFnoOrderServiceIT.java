package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Postgres-backed virtual-money, exact retry and cross-user isolation proof. */
class CustomerPaperFnoOrderServiceIT extends AbstractPostgresIT {
    private static final Instant NOW=Instant.parse("2026-11-16T04:00:00Z");
    private static final LocalDate EXPIRY=LocalDate.parse("2026-11-17");
    private static final String UNDERLYING="NSE_INDEX|Nifty 50";
    private static final String CONTRACT="NSE_FO|61001";

    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;
    @Autowired CustomerPaperWalletService wallets;
    @Autowired MarketSessionCalendar marketCalendar;
    @Autowired PlatformTransactionManager transactionManager;

    private final UpstoxAnalyticsSource source=mock(UpstoxAnalyticsSource.class);
    private final UpstoxFnoV3LiveFeedService live=mock(UpstoxFnoV3LiveFeedService.class);

    private CustomerPaperFnoOrderService engine(boolean rights) {
        return engine(rights, false);
    }
    private CustomerPaperFnoOrderService engine(boolean paperRights, boolean displayRights) {
        return engineAt(NOW, paperRights, displayRights);
    }
    private CustomerPaperFnoOrderService engineAt(Instant instant, boolean paperRights,
                                                  boolean displayRights) {
        return new CustomerPaperFnoOrderService(jdbc,source,live,marketCalendar,
                Clock.fixed(instant,ZoneOffset.UTC),paperRights,displayRights);
    }
    private <T> T tx(java.util.function.Supplier<T> fn) {
        return new TransactionTemplate(transactionManager).execute(status->fn.get());
    }
    private void prepareMarket(String bid,String ask) {
        when(source.enabled()).thenReturn(true);
        when(source.currentFnoOptionContracts(UNDERLYING,EXPIRY)).thenReturn(List.of(
                new UpstoxAnalyticsSource.FnoOptionContract(UNDERLYING,CONTRACT,
                        EXPIRY,new BigDecimal("26000"),"CE",65,
                        new BigDecimal(".05"),"NIFTY TEST CE")));
        when(live.eligiblePaperQuote(CONTRACT)).thenReturn(java.util.Optional.of(
                new UpstoxFnoV3LiveFeedService.PaperQuote(CONTRACT,
                        new BigDecimal(bid),new BigDecimal(ask),
                        NOW.minusSeconds(2),NOW.minusSeconds(1))));
    }
    private UUID customer() {
        UUID suffix=UUID.randomUUID();
        var user=users.createUser("fno-paper-"+suffix,"FNO paper tester",
                "fno-paper-"+suffix+"@example.test","test:customer");
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:id")
                .param("id",user.id()).update();
        return user.id();
    }
    private UUID room(UUID owner,String mode) {
        UUID id=UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer_trading_room
                (id,user_id,name,focus,planned_budget_inr,risk_preference,mode,status)
                VALUES (:id,:owner,:name,'INTRADAY',20000,'BALANCED',:mode,'DRAFT')
                """).param("id",id).param("owner",owner)
                .param("name","FNO test "+id).param("mode",mode).update();
        return id;
    }
    @Test
    void boughtAndSoldOneLotWithExactCashAndRetryIsolation() {
        UUID owner=customer(), stranger=customer();
        UUID paper=room(owner,"PAPER");
        wallets.activate(owner,paper);
        wallets.setPaused(owner,paper,false);
        prepareMarket("99.00","100.00");
        var s=engine(true);
        var buy=tx(()->s.submit(owner,paper,"fno_buy_000001",CONTRACT,
                EXPIRY,"BUY",1,new BigDecimal("101.00")));
        assertThat(buy.quantity()).isEqualTo(65);
        assertThat(buy.fillPrice()).isEqualByComparingTo("100.00"); // Worse ask.
        assertThat(buy.notionalPaise()).isEqualTo(650_000L);
        assertThat(buy.estimatedFeesPaise()).isEqualTo(325L);
        assertThat(buy.cashAfterPaise()).isEqualTo(1_349_675L);
        assertThat(wallets.get(owner,paper).availableCashPaise()).isEqualTo(1_349_675L);
        assertThat(s.positions(owner,paper)).hasSize(1);

        // Idempotent request returns prior immutable fill without a second debit.
        var retry=tx(()->s.submit(owner,paper,"fno_buy_000001",CONTRACT,
                EXPIRY,"BUY",1,new BigDecimal("101.00")));
        assertThat(retry.id()).isEqualTo(buy.id());
        assertThat(wallets.get(owner,paper).availableCashPaise()).isEqualTo(1_349_675L);
        assertThatThrownBy(()->tx(()->s.submit(owner,paper,"fno_buy_000001",
                CONTRACT,EXPIRY,"BUY",2,new BigDecimal("101.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(x->assertThat(((ResponseStatusException)x).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(()->s.orders(stranger,paper))
                .isInstanceOf(ResponseStatusException.class);

        // Selling uses bid, never asks the broker to transact.
        prepareMarket("110.00","111.00");
        var sold=tx(()->s.submit(owner,paper,"fno_sell_00001",CONTRACT,
                EXPIRY,"SELL",1,new BigDecimal("109.00")));
        assertThat(sold.fillPrice()).isEqualByComparingTo("110.00");
        assertThat(sold.notionalPaise()).isEqualTo(715_000L);
        assertThat(sold.estimatedFeesPaise()).isEqualTo(358L);
        assertThat(sold.realizedPnlDeltaPaise()).isEqualTo(64_317L);
        assertThat(wallets.get(owner,paper).availableCashPaise()).isEqualTo(2_064_317L);
        assertThat(wallets.get(owner,paper).realizedPnlPaise()).isEqualTo(64_317L);
        assertThat(s.positions(owner,paper)).isEmpty();
        assertThat(s.orders(owner,paper)).hasSize(2);
        // A second sell cannot open a short option position.
        assertThatThrownBy(()->tx(()->s.submit(owner,paper,"fno_sell_00002",
                CONTRACT,EXPIRY,"SELL",1,new BigDecimal("109.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("oversell");
    }

    @Test
    void rightsPausedLiveRoomAndStalePricesNeverGeneratePaperFill() {
        UUID owner=customer();
        UUID paper=room(owner,"PAPER"), liveRoom=room(owner,"LIVE");
        wallets.activate(owner,paper);
        prepareMarket("99.00","100.00");
        var engine=engine(true);
        assertThatThrownBy(()->tx(()->engine.submit(owner,paper,"fno_paused0001",
                CONTRACT,EXPIRY,"BUY",1,new BigDecimal("101"))))
                .hasMessageContaining("paused");
        wallets.setPaused(owner,paper,false);
        assertThatThrownBy(()->tx(()->engine.submit(owner,liveRoom,"fno_live00001",
                CONTRACT,EXPIRY,"BUY",1,new BigDecimal("101"))))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->tx(()->engine(false).submit(owner,paper,
                "fno_no_rights",CONTRACT,EXPIRY,"BUY",1,new BigDecimal("101"))))
                .hasMessageContaining("rights unverified");
        when(live.eligiblePaperQuote(CONTRACT)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(()->tx(()->engine.submit(owner,paper,"fno_no_quote1",
                CONTRACT,EXPIRY,"BUY",1,new BigDecimal("101"))))
                .hasMessageContaining("bid/ask unavailable");
        assertThat(engine.orders(owner,paper)).isEmpty();
        assertThat(wallets.get(owner,paper).availableCashPaise()).isEqualTo(2_000_000L);
    }
    @Test
    void existingPaperPositionRemainsVisibleButNoPriceValuationWithoutDisplayRights() {
        UUID owner=customer();
        UUID paper=room(owner,"PAPER");
        wallets.activate(owner,paper);
        wallets.setPaused(owner,paper,false);
        prepareMarket("99.00","100.00");
        var restricted=engine(true,false);
        tx(()->restricted.submit(owner,paper,"fno_rights_buy1",CONTRACT,
                EXPIRY,"BUY",1,new BigDecimal("101.00")));

        // Quote reading is independent from showing the owner's own virtual ledger.
        clearInvocations(live);
        var held=restricted.positions(owner,paper);
        assertThat(held).hasSize(1);
        assertThat(held.getFirst().quantity()).isEqualTo(65);
        assertThat(held.getFirst().costBasisPaise()).isEqualTo(650_325L); // premium plus simulated buy fee
        assertThat(held.getFirst().indicativeUnrealizedPnlPaise()).isNull();
        assertThat(held.getFirst().priceFresh()).isFalse();
        verifyNoInteractions(live);

        // Display permission alone does not grant any new PAPER order privilege.
        var displayPermitted=engine(false,true);
        var valued=displayPermitted.positions(owner,paper);
        assertThat(valued).hasSize(1);
        assertThat(valued.getFirst().priceFresh()).isTrue();
        assertThat(valued.getFirst().indicativeUnrealizedPnlPaise()).isNotNull();
        verify(live).eligiblePaperQuote(CONTRACT);
    }

    @Test
    void invalidTicksNonCrossingLimitsAndUnlistedContractsNeverDebitVirtualWallet() {
        UUID owner=customer();
        UUID paper=room(owner,"PAPER");
        wallets.activate(owner,paper);
        wallets.setPaused(owner,paper,false);
        prepareMarket("99.00","100.00");
        var s=engine(true);

        assertThatThrownBy(()->tx(()->s.submit(owner,paper,"fno_bad_tick1",CONTRACT,
                EXPIRY,"BUY",1,new BigDecimal("101.03"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("limit-price tick");
        assertThatThrownBy(()->tx(()->s.submit(owner,paper,"fno_bad_buylim1",CONTRACT,
                EXPIRY,"BUY",1,new BigDecimal("99.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not cross");
        assertThatThrownBy(()->tx(()->s.submit(owner,paper,"fno_bad_selllim1",CONTRACT,
                EXPIRY,"SELL",1,new BigDecimal("101.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not cross");
        assertThatThrownBy(()->tx(()->s.submit(owner,paper,"fno_unlisted_01",
                "NSE_FO|OTHER",EXPIRY,"BUY",1,new BigDecimal("101.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Listed NSE option contract unavailable");
        assertThatThrownBy(()->tx(()->s.submit(owner,paper,"fno_premiumcap1",CONTRACT,
                EXPIRY,"BUY",20,new BigDecimal("101.00"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("premium cap");

        assertThat(wallets.get(owner,paper).availableCashPaise()).isEqualTo(2_000_000L);
        assertThat(s.positions(owner,paper)).isEmpty();
        assertThat(s.orders(owner,paper)).isEmpty();
    }

    @Test
    void staleFutureAndNonSessionExchangeTimestampsNeverCreatePaperFill() {
        UUID owner=customer();
        UUID paper=room(owner,"PAPER");
        wallets.activate(owner,paper);
        wallets.setPaused(owner,paper,false);
        prepareMarket("99.00","100.00");
        var s=engine(true);

        // Receipt may be new while the exchange last trade is old.
        when(live.eligiblePaperQuote(CONTRACT)).thenReturn(java.util.Optional.of(
                new UpstoxFnoV3LiveFeedService.PaperQuote(CONTRACT,
                        new BigDecimal("99"),new BigDecimal("100"),
                        NOW.minusSeconds(30),NOW.minusSeconds(1))));
        assertThatThrownBy(()->tx(()->s.submit(owner,paper,"fno_stale_tick",CONTRACT,
                EXPIRY,"BUY",1,new BigDecimal("101"))))
                .hasMessageContaining("stale or outside session");

        // Reject a feed with an exchange trade timestamp in the future.
        when(live.eligiblePaperQuote(CONTRACT)).thenReturn(java.util.Optional.of(
                new UpstoxFnoV3LiveFeedService.PaperQuote(CONTRACT,
                        new BigDecimal("99"),new BigDecimal("100"),
                        NOW.plusSeconds(3),NOW)));
        assertThatThrownBy(()->tx(()->s.submit(owner,paper,"fno_future_tick",CONTRACT,
                EXPIRY,"BUY",1,new BigDecimal("101"))))
                .hasMessageContaining("stale or outside session");

        // Saturday cannot become a PAPER session just because the fixture has quotes.
        var saturday=engineAt(Instant.parse("2026-11-14T04:00:00Z"),true,false);
        assertThatThrownBy(()->tx(()->saturday.submit(owner,paper,"fno_weekend_01",
                CONTRACT,EXPIRY,"BUY",1,new BigDecimal("101"))))
                .hasMessageContaining("session closed");

        assertThat(s.orders(owner,paper)).isEmpty();
        assertThat(s.positions(owner,paper)).isEmpty();
        assertThat(wallets.get(owner,paper).availableCashPaise()).isEqualTo(2_000_000L);
    }

}
