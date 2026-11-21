package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.marketdata.FnoVerifiedStrategy207212SourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedStrategy207212SourceStore.Proof;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Deterministic synthetic fixtures exercise typed plumbing only.
 * Not real original NSE source evidence or PAPER/LIVE trading proof.
 */
class FnoSkewBasedHedgeResearchServiceTest {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final String ID="strategy.STR-212";
    private static Object invoke(Class<?> owner,String name) throws Exception {
        return invoke(owner,name,new Class<?>[0]);
    }
    private static Object invoke(Class<?> owner,String name,Class<?>[] types,
                                 Object... values) throws Exception {
        Method m=owner.getDeclaredMethod(name,types);
        m.setAccessible(true);
        return m.invoke(null,values);
    }
    @SuppressWarnings("unchecked")
    private static <T> List<T> cast(Object x){return (List<T>)x;}
    private static ObjectMapper json(){return new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);}
    private static FnoSkewBasedHedgeResearchService.Evidence fixture() throws Exception {
        var t=FnoSkewBasedHedgeV1Test.class;
        return new FnoSkewBasedHedgeResearchService.Evidence(
            (FnoSkewBasedHedgeV1.BrokerLongFuture) invoke(t,"held",new Class<?>[]{int.class},1),
            (FnoSkewBasedHedgeV1.FutureContract) invoke(t,"future"),
            (FnoSkewBasedHedgeV1.OptionContract) invoke(t,"higher"),
            (FnoSkewBasedHedgeV1.OptionContract) invoke(t,"lower"),
            (FnoSkewBasedHedgeV1.OptionContract) invoke(t,"call"),
            (FnoSkewBasedHedgeV1.Snapshot) invoke(t,"snapshot"),
            cast(invoke(t,"history")),
            (FnoSkewBasedHedgeV1.Policy) invoke(t,"policy"));
    }
    private static FnoVerifiedStrategy207212SourceStore source(String json){
        var store=mock(FnoVerifiedStrategy207212SourceStore.class);
        when(store.oneCurrent(ID,NOW)).thenReturn(Optional.of(
            new Proof(ID,"UPSTOX_ANALYTICS",json,NOW.minusSeconds(1),"a".repeat(64))));
        return store;
    }
    @Test void sourceRightsDisabledCannotReadBrokerMarketRecords(){
        var store=mock(FnoVerifiedStrategy207212SourceStore.class);
        var row=new FnoSkewBasedHedgeResearchService(store,json(),false).evaluate(NOW);
        assertEquals(ID,row.strategyId());
        assertEquals("BLOCKED_RIGHTS",row.status());
        assertEquals("STR_212_SOURCE_RIGHTS_UNVERIFIED",row.reasonCode());
        verifyNoInteractions(store);
    }
    @Test void typedFixtureReachesUniqueExistingDeterministicEvaluator() throws Exception {
        var store=source(json().writeValueAsString(fixture()));
        var row=new FnoSkewBasedHedgeResearchService(store,json(),true).evaluate(NOW);
        assertEquals(ID,row.strategyId());
        assertEquals("RESEARCH_CANDIDATE",row.status(),row.reasonCode());
        verify(store).oneCurrent(ID,NOW);
    }
    @Test void missingOrUnrecognizedEvidenceNeverBecomesOrder(){
        var none=mock(FnoVerifiedStrategy207212SourceStore.class);
        assertEquals("STR_212_AUTHENTIC_ORIGINAL_SOURCE_MISSING",
            new FnoSkewBasedHedgeResearchService(none,json(),true).evaluate(NOW).reasonCode());
        var tampered=source("{\"policy\":{},\"executeLive\":true}");
        assertEquals("STR_212_PROVIDER_ORIGINAL_TIME_PROVENANCE_INVALID",
            new FnoSkewBasedHedgeResearchService(tampered,json(),true).evaluate(NOW).reasonCode());
    }
    @Test void sourceExceptionsHideCredentials(){
        var store=mock(FnoVerifiedStrategy207212SourceStore.class);
        when(store.oneCurrent(ID,NOW)).thenThrow(new IllegalStateException("SSM SECRET BROKER TOKEN"));
        var row=new FnoSkewBasedHedgeResearchService(store,json(),true).evaluate(NOW);
        assertEquals("STR_212_ORIGINAL_SOURCE_READ_FAILED",row.reasonCode());
        assertFalse(row.toString().contains("TOKEN"));
    }
    @Test void mixedProviderPayloadCannotBorrowFirstProvidersRights() throws Exception {
        var payload=json().writeValueAsString(fixture());
        assertTrue(payload.contains("UPSTOX_ANALYTICS"),"fixture must contain source issuer");
        var other=payload.replace("UPSTOX_ANALYTICS","ANGEL_ONE_SMARTAPI");
        var row=new FnoSkewBasedHedgeResearchService(source(other),json(),true).evaluate(NOW);
        assertEquals("STR_212_PROVIDER_ORIGINAL_TIME_PROVENANCE_INVALID",row.reasonCode());
        assertEquals(ID,row.strategyId());
    }
    @Test void payloadObservedAfterEvidenceFirstKnownIsRejectedWithoutOrders() throws Exception {
        var content=json().writeValueAsString(fixture());
        var store=source(content);
        when(store.oneCurrent(ID,NOW)).thenReturn(Optional.of(
            new Proof(ID,"UPSTOX_ANALYTICS",content,NOW.minusSeconds(75),"a".repeat(64))));
        var row=new FnoSkewBasedHedgeResearchService(store,json(),true).evaluate(NOW);
        assertEquals("STR_212_PROVIDER_ORIGINAL_TIME_PROVENANCE_INVALID",row.reasonCode());
        assertEquals(0,row.contractsResolved());
        assertEquals(0,row.freshQuotes());
    }

}
