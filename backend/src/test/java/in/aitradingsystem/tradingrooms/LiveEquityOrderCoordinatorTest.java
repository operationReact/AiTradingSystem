package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.provider.upstox.CustomerUpstoxOAuthService;
import in.aitradingsystem.provider.upstox.UpstoxApiClient.UpstoxProviderException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Coordinator ordering guarantees, with mocked broker transports only. No test
 * here can reach a real broker: every adapter is a Mockito mock.
 */
class LiveEquityOrderCoordinatorTest {
    private static final String TAG = "MTabcdefgh12";
    private static final String KEY = "idem-key-0001";
    private static final BigDecimal PRICE = new BigDecimal("100.00");
    private static final LiveEquityExecutionPolicy ENABLED = new LiveEquityExecutionPolicy(true);

    private CustomerLiveEquityOrderService orders;
    private CustomerExecutionReadinessService readiness;
    private InstrumentMasterService instruments;
    private CustomerAngelOneSessionService angelOne;
    private CustomerUpstoxOAuthService upstox;
    private UUID owner;
    private UUID room;
    private Instrument angelInstrument;

    @BeforeEach
    void setUp() {
        orders = mock(CustomerLiveEquityOrderService.class);
        readiness = mock(CustomerExecutionReadinessService.class);
        instruments = mock(InstrumentMasterService.class);
        angelOne = mock(CustomerAngelOneSessionService.class);
        upstox = mock(CustomerUpstoxOAuthService.class);
        owner = UUID.randomUUID();
        room = UUID.randomUUID();
        angelInstrument = new Instrument(42L, "NSE_CM", "NSE", "3045", "SBIN-EQ",
            "State Bank", "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
        when(instruments.requireActiveInstrument(42L)).thenReturn(angelInstrument);
    }

    private CustomerExecutionReadinessService.Preflight proposal(String broker, List<String> blockers) {
        return new CustomerExecutionReadinessService.Preflight(room, "LIVE", 42L, broker,
            "SBIN-EQ", "NSE", "BUY", 10, "LIMIT", "DAY", PRICE, new BigDecimal("1000.00"),
            new BigDecimal("100000"), new BigDecimal("20000"), BigDecimal.ZERO,
            true, true, BigDecimal.ZERO, Instant.now(), blockers.isEmpty(), blockers);
    }

    private CustomerLiveEquityOrderService.Order order(String broker, String state) {
        return new CustomerLiveEquityOrderService.Order(UUID.randomUUID(), room, broker,
            42L, "SBIN-EQ", "NSE", "BUY", 10, PRICE, state, null,
            0, "LIVE_EQUITY_ORDER_CLAIMED", Instant.now(), Instant.now());
    }

    /** Stubs a fresh, executable preflight and a newly created claim. */
    private void executableClaim(String broker, String tag) {
        when(readiness.preflight(eq(owner), eq(room), eq(42L), eq("BUY"), eq(10), eq(PRICE)))
            .thenReturn(proposal(broker, List.of()));
        when(orders.claimForSubmission(eq(owner), eq(room), eq(KEY), any(), eq(true)))
            .thenReturn(new CustomerLiveEquityOrderService.Claim(
                order(broker, "SUBMITTING"), true, tag));
    }

    private LiveEquityOrderCoordinator coordinator(LiveEquityExecutionPolicy policy) {
        return new LiveEquityOrderCoordinator(orders, readiness, instruments, angelOne, upstox, policy);
    }

    private LiveEquityOrderCoordinator.Dispatch submit(LiveEquityOrderCoordinator c) {
        return c.submit(owner, room, KEY, 42L, "BUY", 10, PRICE, LiveEquityOrderCoordinator.CONSENT);
    }

    @Test
    void defaultOffGateRefusesBeforeAnyBrokerReadClaimOrRequest() {
        assertThatThrownBy(() -> submit(coordinator(LiveEquityExecutionPolicy.DISABLED)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(orders, readiness, angelOne, upstox);
    }

    @Test
    void missingConsentIsRefusedBeforeAnyBrokerReadOrClaim() {
        assertThatThrownBy(() -> coordinator(ENABLED).submit(owner, room, KEY, 42L, "BUY", 10,
                PRICE, "not the phrase"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
        verifyNoInteractions(orders, readiness, angelOne, upstox);
    }

    @Test
    void replayIsServedFromTheStoredOrderWithoutAnyBrokerReadOrRequest() {
        when(orders.findReplay(eq(owner), eq(room), eq(KEY), eq(42L), eq("BUY"), eq(10), eq(PRICE)))
            .thenReturn(Optional.of(new CustomerLiveEquityOrderService.Claim(
                order("ANGEL_ONE", "ACKNOWLEDGED"), false, null)));

        var result = submit(coordinator(ENABLED));

        assertThat(result.created()).isFalse();
        verify(readiness, never()).preflight(any(), any(), anyLong(), anyString(), anyInt(), any());
        verifyNoInteractions(angelOne, upstox);
        verify(orders, never()).claimForSubmission(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void preflightRunsBeforeTheClaimAndBlockedProposalNeverClaims() {
        when(readiness.preflight(eq(owner), eq(room), eq(42L), eq("BUY"), eq(10), eq(PRICE)))
            .thenReturn(proposal("ANGEL_ONE", List.of("BROKER_POSITIONS_NOT_VERIFIED")));

        assertThatThrownBy(() -> submit(coordinator(ENABLED)))
            .isInstanceOf(ResponseStatusException.class);
        verify(orders, never()).claimForSubmission(any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(angelOne, upstox);
    }

    @Test
    void angelAcknowledgementIsRecordedWithBrokerIdAfterExactlyOneRequest() {
        executableClaim("ANGEL_ONE", TAG);
        when(angelOne.submitVerifiedIntradayLimitOnce(eq(owner), any(ObjectNode.class)))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "ACKNOWLEDGED", "AO-100", "ANGEL_ONE_ORDER_ACK"));
        when(orders.recordDispatchOutcome(eq(owner), eq(room), any(), any(), any(), any()))
            .thenReturn(order("ANGEL_ONE", "ACKNOWLEDGED"));

        var result = submit(coordinator(ENABLED));

        assertThat(result.created()).isTrue();
        verify(angelOne, times(1)).submitVerifiedIntradayLimitOnce(eq(owner), any(ObjectNode.class));
        verify(orders).recordDispatchOutcome(eq(owner), eq(room), any(),
            eq(LiveEquityOrderState.ACKNOWLEDGED), eq("AO-100"), eq("ANGEL_ONE_ORDER_ACK"));
    }

    @Test
    void orderingIsPreflightThenClaimThenSingleRequestThenOutcome() {
        executableClaim("ANGEL_ONE", TAG);
        when(angelOne.submitVerifiedIntradayLimitOnce(eq(owner), any(ObjectNode.class)))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "UNKNOWN", null, "ANGEL_ONE_ORDER_STATUS_UNKNOWN"));
        when(orders.recordDispatchOutcome(eq(owner), eq(room), any(), any(), any(), any()))
            .thenReturn(order("ANGEL_ONE", "UNKNOWN"));

        submit(coordinator(ENABLED));

        var sequence = inOrder(readiness, orders, angelOne);
        sequence.verify(readiness).preflight(eq(owner), eq(room), eq(42L), eq("BUY"), eq(10), eq(PRICE));
        sequence.verify(orders).claimForSubmission(eq(owner), eq(room), eq(KEY), any(), eq(true));
        sequence.verify(angelOne).submitVerifiedIntradayLimitOnce(eq(owner), any(ObjectNode.class));
        sequence.verify(orders).recordDispatchOutcome(eq(owner), eq(room), any(),
            eq(LiveEquityOrderState.UNKNOWN), eq(null), eq("ANGEL_ONE_ORDER_STATUS_UNKNOWN"));
    }

    @Test
    void angelPayloadCarriesTheDurableCorrelationTagAndIntradayLimitDayShape() {
        executableClaim("ANGEL_ONE", TAG);
        when(angelOne.submitVerifiedIntradayLimitOnce(eq(owner), any(ObjectNode.class)))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "UNKNOWN", null, "ANGEL_ONE_ORDER_STATUS_UNKNOWN"));
        when(orders.recordDispatchOutcome(eq(owner), eq(room), any(), any(), any(), any()))
            .thenReturn(order("ANGEL_ONE", "UNKNOWN"));

        submit(coordinator(ENABLED));

        var captor = org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        verify(angelOne).submitVerifiedIntradayLimitOnce(eq(owner), captor.capture());
        ObjectNode payload = captor.getValue();
        assertThat(payload.path("ordertag").asText()).isEqualTo(TAG);
        assertThat(payload.path("producttype").asText()).isEqualTo("INTRADAY");
        assertThat(payload.path("ordertype").asText()).isEqualTo("LIMIT");
        assertThat(payload.path("duration").asText()).isEqualTo("DAY");
        assertThat(payload.path("transactiontype").asText()).isEqualTo("BUY");
        assertThat(payload.path("symboltoken").asText()).isEqualTo("3045");
    }

    @Test
    void ambiguousBrokerResultIsRecordedUnknownAndNeverRetried() {
        executableClaim("ANGEL_ONE", TAG);
        when(angelOne.submitVerifiedIntradayLimitOnce(eq(owner), any(ObjectNode.class)))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "UNKNOWN", null, "ANGEL_ONE_ORDER_STATUS_UNKNOWN"));
        when(orders.recordDispatchOutcome(eq(owner), eq(room), any(), any(), any(), any()))
            .thenReturn(order("ANGEL_ONE", "UNKNOWN"));

        submit(coordinator(ENABLED));

        verify(orders).recordDispatchOutcome(eq(owner), eq(room), any(),
            eq(LiveEquityOrderState.UNKNOWN), eq(null), eq("ANGEL_ONE_ORDER_STATUS_UNKNOWN"));
        verify(angelOne, times(1)).submitVerifiedIntradayLimitOnce(eq(owner), any(ObjectNode.class));
    }

    @Test
    void transportExceptionIsAmbiguousUnknownWithoutLeakingTextAndNeverRetried() {
        executableClaim("ANGEL_ONE", TAG);
        when(angelOne.submitVerifiedIntradayLimitOnce(eq(owner), any(ObjectNode.class)))
            .thenThrow(new RuntimeException("socket reset by peer, token=SECRET"));
        when(orders.recordDispatchOutcome(eq(owner), eq(room), any(), any(), any(), any()))
            .thenReturn(order("ANGEL_ONE", "UNKNOWN"));

        submit(coordinator(ENABLED));

        verify(orders).recordDispatchOutcome(eq(owner), eq(room), any(),
            eq(LiveEquityOrderState.UNKNOWN), eq(null), eq("BROKER_DISPATCH_UNCERTAIN"));
        verify(angelOne, times(1)).submitVerifiedIntradayLimitOnce(eq(owner), any(ObjectNode.class));
    }

    @Test
    void brokerRejectionIsRecordedRejected() {
        executableClaim("ANGEL_ONE", TAG);
        when(angelOne.submitVerifiedIntradayLimitOnce(eq(owner), any(ObjectNode.class)))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "REJECTED", null, "ANGEL_ONE_BROKER_REJECTED"));
        when(orders.recordDispatchOutcome(eq(owner), eq(room), any(), any(), any(), any()))
            .thenReturn(order("ANGEL_ONE", "REJECTED"));

        submit(coordinator(ENABLED));

        verify(orders).recordDispatchOutcome(eq(owner), eq(room), any(),
            eq(LiveEquityOrderState.REJECTED), eq(null), eq("ANGEL_ONE_BROKER_REJECTED"));
    }

    @Test
    void unexpectedOutcomeValueFailsAmbiguousNotAcknowledged() {
        executableClaim("ANGEL_ONE", TAG);
        when(angelOne.submitVerifiedIntradayLimitOnce(eq(owner), any(ObjectNode.class)))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "WHATEVER", "AO-1", "not a known code!"));
        when(orders.recordDispatchOutcome(eq(owner), eq(room), any(), any(), any(), any()))
            .thenReturn(order("ANGEL_ONE", "UNKNOWN"));

        submit(coordinator(ENABLED));

        verify(orders).recordDispatchOutcome(eq(owner), eq(room), any(),
            eq(LiveEquityOrderState.UNKNOWN), eq(null), eq("BROKER_OUTCOME_UNMAPPED"));
    }

    @Test
    void payloadThatCannotBeBuiltIsProvablyRejectedBeforeAnyRequest() {
        executableClaim("ANGEL_ONE", TAG);
        Instrument badToken = new Instrument(42L, "NSE_CM", "NSE", "NOT-NUMERIC",
            "SBIN-EQ", "State Bank", "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
        when(instruments.requireActiveInstrument(42L)).thenReturn(badToken);
        when(orders.recordDispatchOutcome(eq(owner), eq(room), any(), any(), any(), any()))
            .thenReturn(order("ANGEL_ONE", "REJECTED"));

        submit(coordinator(ENABLED));

        verifyNoInteractions(angelOne);
        verify(orders).recordDispatchOutcome(eq(owner), eq(room), any(),
            eq(LiveEquityOrderState.REJECTED), eq(null), eq("ORDER_PAYLOAD_NOT_BUILT"));
    }

    @Test
    void upstoxUnresolvableInstrumentIsRejectedBeforeAnyRequest() {
        when(readiness.preflight(eq(owner), eq(room), eq(42L), eq("BUY"), eq(10), eq(PRICE)))
            .thenReturn(proposal("UPSTOX", List.of()));
        when(orders.claimForSubmission(eq(owner), eq(room), eq(KEY), any(), eq(true)))
            .thenReturn(new CustomerLiveEquityOrderService.Claim(
                order("UPSTOX", "SUBMITTING"), true, TAG));
        when(upstox.cashEquityInstrumentKey(angelInstrument))
            .thenThrow(new UpstoxProviderException("UPSTOX_EQUITY_INSTRUMENT_REFERENCE_STALE_OR_MISSING"));
        when(orders.recordDispatchOutcome(eq(owner), eq(room), any(), any(), any(), any()))
            .thenReturn(order("UPSTOX", "REJECTED"));

        submit(coordinator(ENABLED));

        verify(upstox, never()).submitVerifiedEquityIntradayOnce(any(), any());
        verify(orders).recordDispatchOutcome(eq(owner), eq(room), any(),
            eq(LiveEquityOrderState.REJECTED), eq(null), eq("ORDER_PAYLOAD_NOT_BUILT"));
    }

    @Test
    void upstoxAcknowledgementIsRecordedAfterExactlyOneRequest() {
        when(readiness.preflight(eq(owner), eq(room), eq(42L), eq("BUY"), eq(10), eq(PRICE)))
            .thenReturn(proposal("UPSTOX", List.of()));
        when(orders.claimForSubmission(eq(owner), eq(room), eq(KEY), any(), eq(true)))
            .thenReturn(new CustomerLiveEquityOrderService.Claim(
                order("UPSTOX", "SUBMITTING"), true, TAG));
        when(upstox.cashEquityInstrumentKey(angelInstrument)).thenReturn("NSE_EQ|INE002A01018");
        when(upstox.submitVerifiedEquityIntradayOnce(eq(owner), any(ObjectNode.class)))
            .thenReturn(new CustomerUpstoxOAuthService.EquitySubmission(
                "ACKNOWLEDGED", "UP-77", "UPSTOX_EQUITY_ORDER_ACK"));
        when(orders.recordDispatchOutcome(eq(owner), eq(room), any(), any(), any(), any()))
            .thenReturn(order("UPSTOX", "ACKNOWLEDGED"));

        submit(coordinator(ENABLED));

        verify(upstox, times(1)).submitVerifiedEquityIntradayOnce(eq(owner), any(ObjectNode.class));
        verify(angelOne, never()).submitVerifiedIntradayLimitOnce(any(), any());
        verify(orders).recordDispatchOutcome(eq(owner), eq(room), any(),
            eq(LiveEquityOrderState.ACKNOWLEDGED), eq("UP-77"), eq("UPSTOX_EQUITY_ORDER_ACK"));
    }

    @Test
    void claimRefusalIsPropagatedWithoutAnyBrokerRequest() {
        executableClaim("ANGEL_ONE", TAG);
        when(orders.claimForSubmission(any(), any(), any(), any(), anyBoolean()))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "not executable"));

        assertThatThrownBy(() -> submit(coordinator(ENABLED)))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(angelOne, upstox);
    }

}
