package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.CustomerIdentityStore;
import in.aitradingsystem.user.UserAccountStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/** No NSE, Upstox, market provider or broker dependencies are permitted. */
class CustomerFnoSyntheticSandboxIT extends AbstractPostgresIT {
    private static final String ISSUER = "https://synthetic-test.example";
    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;
    @Autowired CustomerPaperWalletService wallets;

    @Test
    void syntheticBuySellIdempotencyAndReconciliationNeverTouchRealPaperWallet() {
        UUID user = customer(), room = room(user,"FNO");
        wallets.activate(user,room);
        var before=wallets.get(user,room);
        var app = controller(user);
        var initially = app.state(room,auth()).getBody();
        assertThat(initially.mode()).isEqualTo("SYNTHETIC_PRACTICE");
        assertThat(initially.synthetic()).isTrue();
        assertThat(initially.exchangeDataUsed()).isFalse();
        assertThat(initially.brokerOrdersEnabled()).isFalse();
        assertThat(initially.contracts()).hasSize(2);
        assertThat(initially.availableCashPaise()).isEqualTo(2_000_000L);
        assertThat(initially.recordedFills()).isZero();

        var buy = app.submit(room,"synth_buy_00001",
            new CustomerFnoSyntheticSandboxController.OrderRequest("SIM_FNO_CE","BUY",1),
            auth()).getBody();
        assertThat(buy.side()).isEqualTo("BUY");
        assertThat(buy.quantity()).isEqualTo(10);
        assertThat(buy.fillPrice()).isEqualByComparingTo("100.00");
        assertThat(buy.cashAfterPaise()).isEqualTo(1_899_900L); // 1000 + 1 fee
        var replay = app.submit(room,"synth_buy_00001",
            new CustomerFnoSyntheticSandboxController.OrderRequest("SIM_FNO_CE","BUY",1),
            auth()).getBody();
        assertThat(replay.id()).isEqualTo(buy.id());
        assertThatThrownBy(()->app.submit(room,"synth_buy_00001",
            new CustomerFnoSyntheticSandboxController.OrderRequest("SIM_FNO_CE","BUY",2),
            auth())).isInstanceOf(ResponseStatusException.class)
            .satisfies(ex->assertThat(((ResponseStatusException)ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));

        var afterBuy=app.state(room,auth()).getBody();
        assertThat(afterBuy.positions()).hasSize(1);
        assertThat(afterBuy.ledgerReconciled()).isTrue();
        assertThat(afterBuy.recordedFills()).isEqualTo(1);

        var sell=app.submit(room,"synth_sell_0001",
            new CustomerFnoSyntheticSandboxController.OrderRequest("SIM_FNO_CE","SELL",1),
            auth()).getBody();
        assertThat(sell.fillPrice()).isEqualByComparingTo("99.00");
        assertThat(sell.realizedPnlDeltaPaise()).isEqualTo(-1_200L); // ₹10 spread + ₹2 fees
        var afterSell=app.state(room,auth()).getBody();
        assertThat(afterSell.positions()).isEmpty();
        assertThat(afterSell.recordedFills()).isEqualTo(2);
        assertThat(afterSell.orders()).hasSize(2);
        assertThat(afterSell.availableCashPaise()).isEqualTo(1_998_800L);
        assertThat(afterSell.realizedPnlPaise()).isEqualTo(-1_200L);
        assertThat(afterSell.ledgerReconciled()).isTrue();

        assertThat(wallets.get(user,room).availableCashPaise())
            .isEqualTo(before.availableCashPaise());
        assertThat(wallets.ledger(user,room)).hasSize(1);
        assertThat(jdbc.sql("""
            SELECT COUNT(*) FROM customer_paper_fno_order WHERE room_id=:room
            """).param("room",room).query(Long.class).single()).isZero();
    }

    @Test
    void rejectsUnknownContractOversellAndDoesNotChangeRealPaperPause() {
        UUID user=customer(),room=room(user,"FNO");
        wallets.activate(user,room);
        var app=controller(user);
        reject(app,room,"SIM_FNO_FAKE","BUY",1,HttpStatus.BAD_REQUEST);
        reject(app,room,"SIM_FNO_CE","SELL",1,HttpStatus.CONFLICT);
        wallets.setPaused(user,room,true);
        var practice = app.submit(room,"synth_buy_paused_01",
            new CustomerFnoSyntheticSandboxController.OrderRequest("SIM_FNO_CE","BUY",1),
            auth()).getBody();
        assertThat(practice.quantity()).isEqualTo(10);
        assertThat(wallets.get(user,room).halted()).isTrue();
        assertThat(app.state(room,auth()).getBody().recordedFills()).isEqualTo(1);
    }

    @Test
    void rejectsForeignEquityLiveAndUnauthenticatedRooms() {
        UUID owner=customer(),other=customer(),fno=room(owner,"FNO"),
            equity=room(owner,"EQUITY");
        wallets.activate(owner,fno);
        wallets.activate(owner,equity);
        assertThatThrownBy(()->controller(other).state(fno,auth()))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex->assertThat(((ResponseStatusException)ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(()->controller(owner).state(equity,auth()))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex->assertThat(((ResponseStatusException)ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(()->controller(owner).state(fno,null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex->assertThat(((ResponseStatusException)ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM customer_fno_sandbox_order WHERE room_id=:room")
            .param("room",fno).query(Long.class).single()).isZero();
    }

    private void reject(CustomerFnoSyntheticSandboxController app,UUID room,
            String code,String side,int lots,HttpStatus status) {
        assertThatThrownBy(()->app.submit(room,"synth_reject_0001",
            new CustomerFnoSyntheticSandboxController.OrderRequest(code,side,lots),
            auth())).isInstanceOf(ResponseStatusException.class)
            .satisfies(ex->assertThat(((ResponseStatusException)ex).getStatusCode())
                .isEqualTo(status));
    }

    private CustomerFnoSyntheticSandboxController controller(UUID owner) {
        var store=mock(CustomerIdentityStore.class);
        when(store.find("cognito",ISSUER,"test-subject")).thenReturn(
            Optional.of(new CustomerIdentityStore.CustomerAccount(
                owner,"ACTIVE","CUSTOMER","Tester","test@example.test")));
        return new CustomerFnoSyntheticSandboxController(jdbc,store);
    }

    private static JwtAuthenticationToken auth() {
        var jwt=Jwt.withTokenValue("test").header("alg","none")
            .claim("iss",ISSUER).subject("test-subject")
            .issuedAt(Instant.now().minusSeconds(10))
            .expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private UUID customer() {
        UUID id=UUID.randomUUID();
        var user=users.createUser("synthetic-fno-"+id,
            "Synthetic FNO","synthetic-"+id+"@example.test","test");
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:id")
            .param("id",user.id()).update();
        return user.id();
    }

    private UUID room(UUID owner,String scope) {
        UUID id=UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO customer_trading_room
            (id,user_id,name,focus,planned_budget_inr,risk_preference,mode,
             market_scope,status)
            VALUES (:id,:owner,:name,'SWING',20000,'BALANCED','PAPER',:scope,'DRAFT')
            """).param("id",id).param("owner",owner)
            .param("name","Synthetic room "+id).param("scope",scope).update();
        return id;
    }
}
