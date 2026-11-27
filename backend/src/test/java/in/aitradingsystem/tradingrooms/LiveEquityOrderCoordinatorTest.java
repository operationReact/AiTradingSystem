package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Coordinator ordering guarantees, with mocked broker transports only. No
 * test here can reach a real broker: every adapter is a Mockito mock.
 */
class LiveEquityOrderCoordinatorTest {
    private static final String TAG = "MTabcdefgh12";
    private static final String KEY = "idem-key-0001";

    private CustomerLiveEquityOrderService orders;
    private InstrumentMasterService instruments;
    private CustomerAngelOneSessionService angelOne;
    private CustomerUpstoxOAuthService upstox;
    private UUID owner;
    private UUID room;
    private Instrument angelInstrument;

    @BeforeEach
    void setUp() {
        orders = mock(CustomerLiveEquityOrderService.class);
        instruments = mock(InstrumentMasterService.class);
        angelOne = mock(CustomerAngelOneSessionService.class);
        upstox = mock(CustomerUpstoxOAuthService.class);
        owner = UUID.randomUUID();
        room = UUID.randomUUID();
        angelInstrument = new Instrument(42L, "NSE_CM", "NSE", "3045", "SBIN-EQ",
            "State Bank", "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
        when(instruments.requireActiveInstrument(42L)).thenReturn(angelInstrument);
    }

    private static final LiveEquityExecutionPolicy ENABLED =
        new LiveEquityExecutionPolicy(true, true);

    private CustomerLiveEquityOrderService.Order order(String broker, String state) {
        return new CustomerLiveEquityOrderService.Order(UUID.randomUUID(), room, broker,
            42L, "SBIN-EQ", "NSE", "BUY", 10, new BigDecimal("100.00"), state, null,
            0, "LIVE_EQUITY_ORDER_CLAIMED", Instant.now(), Instant.now());
    }

    private void claimCreated(String broker, String tag) {
        when(orders.claimForSubmission(eq(owner), eq(room), eq(KEY), eq(42L), eq("BUY"),
                eq(10), eq(new BigDecimal("100.00")), anyBoolean()))
            .thenReturn(new CustomerLiveEquityOrderService.Claim(
                order(broker, "SUBMITTING"), true, tag));
    }

    private LiveEquityOrderCoordinator coordinator(LiveEquityExecutionPolicy policy) {
        return new LiveEquityOrderCoordinator(orders, instruments, angelOne, upstox, policy);
    }

    private void submit(LiveEquityOrderCoordinator c) {
        c.submit(owner, room, KEY, 42L, "BUY", 10, new BigDecimal("100.00"),
            LiveEquityOrderCoordinator.CONSENT);
    }

    @Test
    void defaultOffGateRefusesBeforeAnyClaimOrBrokerRequest() {
        assertThatThrownBy(() -> submit(coordinator(LiveEquityExecutionPolicy.DISABLED)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(orders, angelOne, upstox);
    }

    @Test
    void angelAcknowledgementIsRecordedWithBrokerIdAfterExactlyOneRequest() {
        claimCreated("ANGEL_ONE", TAG);
        when(angelOne.submitVerifiedIntradayLimitOnce(eq(owner), any(ObjectNode.class)))
            .thenReturn(new CustomerAngelOneSessionService.OrderSubmission(
                "ACKNOWLEDGED", "AO-100", "ANGEL_ONE_ORDER_ACK"));
        when(orders.recordDispatchOutcome(eq(owner), eq(room), any(), any(), any(), any()))
            .thenReturn(order("ANGEL_ONE", "ACKNOWLEDGED"));

        var result = coordinator(ENABLED).submit(owner, room, KEY, 42L, "BUY", 10,
            new BigDecimal("100.00"), LiveEquityOrderCoordinator.CONSENT);

        assertThat(result.created()).isTrue();
        verify(angelOne, times(1)).submitVerifiedIntradayLimitOnce(eq(owner), any(ObjectNode.class));
        verify(orders).recordDispatchOutcome(eq(owner), eq(room), any(),
            eq(LiveEquityOrderState.ACKNOWLEDGED), eq("AO-100"), eq("ANGEL_ONE_ORDER_ACK"));
    }

    @Test
    void angelPayloadCarriesTheDurableCorrelationTagAndIntradayLimitDayShape() {
        claimCreated("ANGEL_ONE", TAG);
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
        claimCreated("ANGEL_ONE", TAG);
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
        claimCreated("ANGEL_ONE", TAG);
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
        claimCreated("ANGEL_ONE", TAG);
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
        claimCreated("ANGEL_ONE", TAG);
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
    void replayReturnsStoredOrderAndNeverDispatchesAgain() {
        when(orders.claimForSubmission(eq(owner), eq(room), eq(KEY), eq(42L), eq("BUY"),
                eq(10), eq(new BigDecimal("100.00")), anyBoolean()))
            .thenReturn(new CustomerLiveEquityOrderService.Claim(
                order("ANGEL_ONE", "UNKNOWN"), false, null));

        var result = coordinator(ENABLED).submit(owner, room, KEY, 42L, "BUY", 10,
            new BigDecimal("100.00"), LiveEquityOrderCoordinator.CONSENT);

        assertThat(result.created()).isFalse();
        verifyNoInteractions(angelOne, upstox);
        verify(orders, never()).recordDispatchOutcome(any(), any(), any(), any(), any(), any());
    }

    @Test
    void payloadThatCannotBeBuiltIsProvablyRejectedBeforeAnyRequest() {
        claimCreated("ANGEL_ONE", TAG);
        Instrument badToken = new Instrument(42L, "NSE_CM", "NSE", "NOT-NUMERIC",
            "SBIN-EQ", "State Bank", "EQ", null, null, 1, new BigDecimal("0.05"),
            UUID.randomUUID());
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
        when(orders.claimForSubmission(eq(owner), eq(room), eq(KEY), eq(42L), eq("BUY"),
                eq(10), eq(new BigDecimal("100.00")), anyBoolean()))
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
        when(orders.claimForSubmission(eq(owner), eq(room), eq(KEY), eq(42L), eq("BUY"),
                eq(10), eq(new BigDecimal("100.00")), anyBoolean()))
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
    void consentPhraseIsRequiredAndForwardedToTheClaim() {
        when(orders.claimForSubmission(eq(owner), eq(room), eq(KEY), eq(42L), eq("BUY"),
                eq(10), eq(new BigDecimal("100.00")), eq(false)))
            .thenThrow(new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "consent"));

        assertThatThrownBy(() -> coordinator(ENABLED).submit(owner, room, KEY, 42L, "BUY", 10,
                new BigDecimal("100.00"), "wrong phrase"))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(angelOne, upstox);
    }

    @Test
    void claimRefusalIsPropagatedWithoutAnyBrokerRequest() {
        when(orders.claimForSubmission(anyCoordinatorUuid(), anyCoordinatorUuid(), any(),
                anyLong(), any(), anyInt(), any(), anyBoolean()))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "not executable"));

        assertThatThrownBy(() -> submit(coordinator(ENABLED)))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(angelOne, upstox);
    }

    private static UUID anyCoordinatorUuid() {
        return any(UUID.class);
    }
}
