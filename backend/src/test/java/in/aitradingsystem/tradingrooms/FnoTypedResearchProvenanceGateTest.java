package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The fixtures are invented and intentionally do NOT verify NSE source rights. */
class FnoTypedResearchProvenanceGateTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String P="UPSTOX_ANALYTICS";
    static final Instant AS_OF=Instant.parse("2026-11-20T05:00:00Z");
    static final Instant KNOWN=AS_OF.minusSeconds(5);

    @Test void allNestedProvidersAndOriginalFirstKnownTimesMustBeConsistent(){
        var source="""
            {"snapshot":{"provider":"UPSTOX_ANALYTICS",
                         "firstKnownAt":"2026-11-20T04:59:20Z",
                         "quotes":[
                           {"provider":"UPSTOX_ANALYTICS",
                            "firstKnownAt":"2026-11-20T04:59:15Z"},
                           {"provider":"UPSTOX_ANALYTICS",
                            "brokerFirstKnownAt":"2026-11-19T09:30:00Z"}
                         ]}}
            """;
        assertTrue(FnoTypedResearchProvenanceGate.valid(
            JSON,source,P,KNOWN,AS_OF));
        assertFalse(FnoTypedResearchProvenanceGate.valid(
            JSON,source,"ANGEL_ONE_SMARTAPI",KNOWN,AS_OF));
    }

    @Test void nestedProviderSwitchOrTimestampAfterEnvelopeBlocksUse(){
        var switched="""
            {"snapshot":{"provider":"UPSTOX_ANALYTICS",
                         "quote":{"provider":"ANGEL_ONE_SMARTAPI",
                                   "firstKnownAt":"2026-11-20T04:59:20Z"}}}
            """;
        assertFalse(FnoTypedResearchProvenanceGate.valid(
            JSON,switched,P,KNOWN,AS_OF));
        var hindsight="""
            {"snapshot":{"provider":"UPSTOX_ANALYTICS",
                         "quote":{"firstKnownAt":"2026-11-20T04:59:59Z"}}}
            """;
        assertFalse(FnoTypedResearchProvenanceGate.valid(
            JSON,hindsight,P,KNOWN,AS_OF));
    }

    @Test void missingProviderOrFirstKnownIsNotOriginalSourceProof(){
        assertFalse(FnoTypedResearchProvenanceGate.valid(
            JSON,"{\"firstKnownAt\":\"2026-11-20T04:59:15Z\"}",
            P,KNOWN,AS_OF));
        assertFalse(FnoTypedResearchProvenanceGate.valid(
            JSON,"{\"provider\":\"UPSTOX_ANALYTICS\"}",
            P,KNOWN,AS_OF));
    }

    @Test void numericalOrNullTimestampsCannotBeReinterpretedAsPointInTime(){
        assertFalse(FnoTypedResearchProvenanceGate.valid(
            JSON,"{\"provider\":\"UPSTOX_ANALYTICS\",\"firstKnownAt\":1763614780}",
            P,KNOWN,AS_OF));
        assertFalse(FnoTypedResearchProvenanceGate.valid(
            JSON,"{\"provider\":\"UPSTOX_ANALYTICS\",\"firstKnownAt\":null}",
            P,KNOWN,AS_OF));
    }

    @Test void sourceCannotBeKnownAfterEvaluationOrUseUnlicensedProvider(){
        var s="{\"provider\":\"UPSTOX_ANALYTICS\",\"firstKnownAt\":\"2026-11-20T04:59:15Z\"}";
        assertFalse(FnoTypedResearchProvenanceGate.valid(
            JSON,s,P,AS_OF.plusSeconds(1),AS_OF));
        assertFalse(FnoTypedResearchProvenanceGate.valid(
            JSON,s,"SYNTHETIC_BACKTEST",KNOWN,AS_OF));
    }

    @Test void deepJsonBombIsRejectedWithoutEvaluatingAnOrder(){
        var prefix="{\"provider\":\"UPSTOX_ANALYTICS\",\"firstKnownAt\":\"2026-11-20T04:59:15Z\",\"x\":";
        var bomb=prefix+"[".repeat(50)+"0"+"]".repeat(50)+"}";
        assertFalse(FnoTypedResearchProvenanceGate.valid(
            JSON,bomb,P,KNOWN,AS_OF));
    }
}
