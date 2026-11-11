package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.MarketLastTickStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.UserAccountStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

/** Real Postgres proof of owner isolation, position/cash conservation and retry
 *  idempotency. Uses a frozen market-session clock; never calls a broker. */
class CustomerPaperOrderServiceIT extends AbstractPostgresIT {
    private static final Instant NOW = Instant.parse("2026-11-12T04:00:00Z");

    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;
    @Autowired InstrumentMasterService instruments;
    @Autowired MarketLastTickStore lastTicks;
    @Autowired MarketSessionCalendar marketCalendar;
    @Autowired CustomerPaperWalletService wallets;

    @Test
    void paperBuyRetrySellAndTenantIsolationNeverTouchLive() {
        long instrument = instrument();
        UUID owner = customer();
        UUID stranger = customer();
        UUID room = room(owner,"PAPER");
        wallets.activate(owner,room);
        wallets.setPaused(owner,room,false);
        recordTick(instrument, "100.00", NOW.minusSeconds(2));
        var engine = engine();

        var bought = engine.submit(owner,room,"request_buy_0001",instrument,
            "BUY",1,new BigDecimal("100.05"));
        assertThat(bought.fillPrice()).isEqualByComparingTo("100.00");
        assertThat(bought.notionalPaise()).isEqualTo(10_000L);
        assertThat(bought.feesPaise()).isEqualTo(100L);
        assertThat(engine.orders(owner,room)).hasSize(1);
        assertThat(engine.positions(owner,room)).hasSize(1);
        assertThat(engine.positions(owner,room).getFirst().quantity()).isEqualTo(1);
        assertThat(wallets.get(owner,room).availableCashPaise()).isEqualTo(1_989_900L);
        var repeated = engine.submit(owner,room,"request_buy_0001",instrument,
            "BUY",1,new BigDecimal("100.05"));
        assertThat(repeated.id()).isEqualTo(bought.id());
        assertThat(wallets.get(owner,room).availableCashPaise()).isEqualTo(1_989_900L);
        assertThatThrownBy(() -> engine.submit(owner,room,"request_buy_0001",instrument,
            "BUY",2,new BigDecimal("100.05")))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException)ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));

        recordTick(instrument,"101.00",NOW.minusSeconds(1));
        var sold = engine.submit(owner,room,"request_sell_001",instrument,
            "SELL",1,new BigDecimal("100.00"));
        assertThat(sold.notionalPaise()).isEqualTo(10_100L);
        assertThat(sold.realizedPnlDeltaPaise()).isEqualTo(-100L);
        assertThat(wallets.get(owner,room).availableCashPaise()).isEqualTo(1_999_900L);
        assertThat(engine.positions(owner,room)).isEmpty();
        assertThat(engine.orders(owner,room)).hasSize(2);

        assertThatThrownBy(() -> engine.orders(stranger,room))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> engine.submit(stranger,room,"wrong_owner_01",
            instrument,"BUY",1,new BigDecimal("110")))
            .isInstanceOf(ResponseStatusException.class);

        UUID live = room(owner,"LIVE");
        assertThatThrownBy(() -> engine.submit(owner,live,"live_not_paper",
            instrument,"BUY",1,new BigDecimal("110")))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void staleQuoteAndPausedWalletCannotGenerateFills() {
        long instrument = instrument();
        UUID owner = customer();
        UUID room = room(owner,"PAPER");
        wallets.activate(owner,room);
        recordTick(instrument,"100.00",NOW.minusSeconds(2));
        var engine = engine();
        assertThatThrownBy(() -> engine.submit(owner,room,"paused_order_1",
            instrument,"BUY",1,new BigDecimal("100.00")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("paused");
        wallets.setPaused(owner,room,false);
        recordTick(instrument,"100.00",NOW.minusSeconds(40));
        assertThatThrownBy(() -> engine.submit(owner,room,"stale_order_1",
            instrument,"BUY",1,new BigDecimal("100.00")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Fresh verified broker quote");
        assertThat(engine.orders(owner,room)).isEmpty();
        assertThat(wallets.get(owner,room).availableCashPaise()).isEqualTo(2_000_000L);
    }

    private CustomerPaperOrderService engine() {
        return new CustomerPaperOrderService(jdbc,instruments,lastTicks,
            marketCalendar,Clock.fixed(NOW,ZoneOffset.UTC));
    }

    private UUID customer() {
        String subject = UUID.randomUUID().toString();
        var user = users.createUser("paper-order-" + subject, "Paper order",
            "paper-order-"+subject+"@example.test","test:customer");
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:id")
            .param("id",user.id()).update();
        return user.id();
    }

    private UUID room(UUID owner,String mode) {
        UUID room = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer_trading_room
                    (id,user_id,name,focus,planned_budget_inr,risk_preference,mode,status)
                VALUES (:id,:user,:name,'INTRADAY',20000,'BALANCED',:mode,'DRAFT')
                """).param("id",room).param("user",owner)
            .param("name","Paper-order-"+room)
            .param("mode",mode).update();
        return room;
    }

    private long instrument() {
        String data = """
                [
                  {"token":"2885","symbol":"RELIANCE-EQ","name":"RELIANCE",
                   "expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ",
                   "exch_seg":"nse_cm","tick_size":"5"},
                  {"token":"3045","symbol":"SBIN-EQ","name":"SBIN",
                   "expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ",
                   "exch_seg":"nse_cm","tick_size":"5"},
                  {"token":"11128","symbol":"SBIN-AF","name":"SBIN",
                   "expiry":"","strike":"-1","lotsize":"1","instrumenttype":"EQ",
                   "exch_seg":"nse_cm","tick_size":"5"}
                ]
                """;
        var imported = instruments.importSource(data.getBytes(StandardCharsets.UTF_8));
        assertThat(imported.status()).isEqualTo("SUCCEEDED");
        return instruments.activeInstrumentBySymbol("NSE","RELIANCE-EQ")
            .orElseThrow().id();
    }

    private void recordTick(long instrument,String price,Instant when) {
        jdbc.sql("""
                INSERT INTO market_last_tick
                  (instrument_id,session_date,ltp,exchange_at,updated_at)
                VALUES (:id,DATE '2026-11-12',:ltp,:time,NOW())
                ON CONFLICT (instrument_id) DO UPDATE SET
                    ltp=EXCLUDED.ltp,exchange_at=EXCLUDED.exchange_at,updated_at=NOW()
                """).param("id",instrument)
            .param("ltp",new BigDecimal(price)).param("time",when).update();
    }
}
