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
class FnoExpiryRiskNeutralizationResearchServiceTest {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    static final String ID="strategy.STR-209";
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
    private static ObjectMapper json(){return new ObjectMapper().findAndRegisterModules();}
    private static FnoExpiryRiskNeutralizationResearchService.Evidence fixture() throws Exception {
        var t=FnoExpiryRiskNeutralizationV1Test.class;
        return new FnoExpiryRiskNeutralizationResearchService.Evidence(
            (FnoExpiryRiskNeutralizationV1.BrokerBook) invoke(t,"book"),
            (FnoExpiryRiskNeutralizationV1.Snapshot) invoke(t,"snapshot"),
            (FnoExpiryRiskNeutralizationV1.ExpiryEvidence) invoke(t,"expiry"),
            cast(invoke(t,"history")),
            (FnoExpiryRiskNeutralizationV1.Policy) invoke(t,"policy"));
    }
    private static FnoVerifiedStrategy207212SourceStore source(String json){
        var store=mock(FnoVerifiedStrategy207212SourceStore.class);
        when(store.oneCurrent(ID,NOW)).thenReturn(Optional.of(
            new Proof(ID,"UPSTOX_ANALYTICS",json,NOW.minusSeconds(45),"a".repeat(64))));
        return store;
    }
    @Test void sourceRightsDisabledCannotReadBrokerMarketRecords(){
        var store=mock(FnoVerifiedStrategy207212SourceStore.class);
        var row=new FnoExpiryRiskNeutralizationResearchService(store,json(),false).evaluate(NOW);
        assertEquals(ID,row.strategyId());
        assertEquals("BLOCKED_RIGHTS",row.status());
        assertEquals("STR_209_SOURCE_RIGHTS_UNVERIFIED",row.reasonCode());
        verifyNoInteractions(store);
    }
    @Test void typedFixtureReachesUniqueExistingDeterministicEvaluator() throws Exception {
        var store=source(json().writeValueAsString(fixture()));
        var row=new FnoExpiryRiskNeutralizationResearchService(store,json(),true).evaluate(NOW);
        assertEquals(ID,row.strategyId());
        assertEquals("RESEARCH_CLOSE_ALL",row.status(),row.reasonCode());
        verify(store).oneCurrent(ID,NOW);
    }
    @Test void missingOrUnrecognizedEvidenceNeverBecomesOrder(){
        var none=mock(FnoVerifiedStrategy207212SourceStore.class);
        assertEquals("STR_209_AUTHENTIC_ORIGINAL_SOURCE_MISSING",
            new FnoExpiryRiskNeutralizationResearchService(none,json(),true).evaluate(NOW).reasonCode());
        var tampered=source("{\"policy\":{},\"executeLive\":true}");
        assertEquals("STR_209_ORIGINAL_TYPED_INPUT_INVALID",
            new FnoExpiryRiskNeutralizationResearchService(tampered,json(),true).evaluate(NOW).reasonCode());
    }
    @Test void sourceExceptionsHideCredentials(){
        var store=mock(FnoVerifiedStrategy207212SourceStore.class);
        when(store.oneCurrent(ID,NOW)).thenThrow(new IllegalStateException("SSM SECRET BROKER TOKEN"));
        var row=new FnoExpiryRiskNeutralizationResearchService(store,json(),true).evaluate(NOW);
        assertEquals("STR_209_ORIGINAL_SOURCE_READ_FAILED",row.reasonCode());
        assertFalse(row.toString().contains("TOKEN"));
    }
}
