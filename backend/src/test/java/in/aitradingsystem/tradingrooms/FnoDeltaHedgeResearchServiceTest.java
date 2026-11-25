package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.marketdata.FnoVerifiedStrategy203206SourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedStrategy203206SourceStore.Proof;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The original deterministic test fixtures are NOT broker-original market data.
 * Tests prove plumbing, fail-closed source rights, and NON-EXECUTABILITY only. */
class FnoDeltaHedgeResearchServiceTest {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final String ID="strategy.STR-204";
    private static Object field(Class<?> owner,String name) throws Exception {
        Field f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(null);
    }
    private static Object method(Class<?> owner,String name,Class<?>[] sig,
                                 Object... args) throws Exception {
        Method m=owner.getDeclaredMethod(name,sig);m.setAccessible(true);
        return m.invoke(null,args);
    }
    @SuppressWarnings("unchecked")
    private static <T> List<T> cast(Object value){return (List<T>)value;}
    private static ObjectMapper json(){return new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);}
    private static FnoDeltaHedgeResearchService.Evidence fixture() throws Exception {
        return new FnoDeltaHedgeResearchService.Evidence(
            cast(method(FnoDeltaHedgeV1Test.class,"options",new Class<?>[0])),
            (FnoDeltaHedgeV1.FutureInstrument) method(FnoDeltaHedgeV1Test.class,"future",new Class<?>[0]),
            (FnoDeltaHedgeV1.FuturesPosition) method(FnoDeltaHedgeV1Test.class,"heldFutures",
                new Class<?>[]{int.class},0),
            (FnoDeltaHedgeV1.Snapshot) method(FnoDeltaHedgeV1Test.class,"snapshot",new Class<?>[0]),
            cast(method(FnoDeltaHedgeV1Test.class,"history",new Class<?>[0])),
            (FnoDeltaHedgeV1.Policy) method(FnoDeltaHedgeV1Test.class,"policy",new Class<?>[0]));
    }
    private static FnoVerifiedStrategy203206SourceStore source(String content){
        var store=mock(FnoVerifiedStrategy203206SourceStore.class);
        when(store.oneCurrent(ID,NOW)).thenReturn(Optional.of(new Proof(
            ID,"UPSTOX_ANALYTICS",content,NOW.minusSeconds(1),"a".repeat(64))));
        return store;
    }
    @Test void deniedResearchRightsDoNotReadOrPlaceBrokerOrders(){
        var store=mock(FnoVerifiedStrategy203206SourceStore.class);
        var result=new FnoDeltaHedgeResearchService(store,json(),false).evaluate(NOW);
        assertEquals("strategy.STR-204",result.strategyId());
        assertEquals("BLOCKED_RIGHTS",result.status());
        assertEquals("STR_204_SOURCE_RIGHTS_UNVERIFIED",result.reasonCode());
        verifyNoInteractions(store);
    }
    @Test void typedOriginalShapeInvokesDistinctExistingEvaluator() throws Exception {
        var store=source(json().writeValueAsString(fixture()));
        var row=new FnoDeltaHedgeResearchService(store,json(),true).evaluate(NOW);
        assertEquals(ID,row.strategyId());
        assertEquals("RESEARCH_REBALANCE",row.status(),row.reasonCode());
        assertFalse(row.inputState().contains("ORDER_PLACED"));
        verify(store).oneCurrent(ID,NOW);
    }
    @Test void emptyAndMaliciousPayloadsFailClosed() {
        var missing=mock(FnoVerifiedStrategy203206SourceStore.class);
        assertEquals("STR_204_GENUINE_ORIGINAL_SOURCE_EVIDENCE_MISSING",
            new FnoDeltaHedgeResearchService(missing,json(),true).evaluate(NOW).reasonCode());
        var invalid=source("{\"policy\":{},\"untrustedExecutionFlag\":true}");
        assertEquals("STR_204_PROVIDER_ORIGINAL_TIME_PROVENANCE_INVALID",
            new FnoDeltaHedgeResearchService(invalid,json(),true).evaluate(NOW).reasonCode());
    }
    @Test void sourceAccessFailureRedactsPrivateBrokerTokens(){
        var store=mock(FnoVerifiedStrategy203206SourceStore.class);
        when(store.oneCurrent(ID,NOW)).thenThrow(
            new IllegalStateException("secret actual brokerage token"));
        var row=new FnoDeltaHedgeResearchService(store,json(),true).evaluate(NOW);
        assertEquals("STR_204_ORIGINAL_SOURCE_READ_FAILED",row.reasonCode());
        assertFalse(row.toString().contains("secret"));
    }
    @Test void mixedProviderPayloadCannotBorrowFirstProvidersRights() throws Exception {
        var payload=json().writeValueAsString(fixture());
        assertTrue(payload.contains("UPSTOX_ANALYTICS"),"fixture must contain source issuer");
        var other=payload.replace("UPSTOX_ANALYTICS","ANGEL_ONE_SMARTAPI");
        var row=new FnoDeltaHedgeResearchService(source(other),json(),true).evaluate(NOW);
        assertEquals("STR_204_PROVIDER_ORIGINAL_TIME_PROVENANCE_INVALID",row.reasonCode());
        assertEquals(ID,row.strategyId());
    }
    @Test void payloadObservedAfterEvidenceFirstKnownIsRejectedWithoutOrders() throws Exception {
        var content=json().writeValueAsString(fixture());
        var store=source(content);
        when(store.oneCurrent(ID,NOW)).thenReturn(Optional.of(
            new Proof(ID,"UPSTOX_ANALYTICS",content,NOW.minusSeconds(75),"a".repeat(64))));
        var row=new FnoDeltaHedgeResearchService(store,json(),true).evaluate(NOW);
        assertEquals("STR_204_PROVIDER_ORIGINAL_TIME_PROVENANCE_INVALID",row.reasonCode());
        assertEquals(0,row.contractsResolved());
        assertEquals(0,row.freshQuotes());
    }

}
