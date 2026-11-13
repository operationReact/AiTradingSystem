package in.aitradingsystem.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

class UpstoxBseCrosswalkRepositoryIT extends AbstractPostgresIT {
    @Autowired UpstoxBseCrosswalkRepository crosswalk;

    @Test
    @Transactional
    void exactBseCodeAndSymbolMayPromoteOnlyUntypedCashEquity() {
        var source=new UpstoxBseCrosswalkParser.Parsed(150,100,50,verified(100));
        var state=crosswalk.publish(source,"aabbccddeeff0011".repeat(4),Instant.now());
        assertThat(state.version()).isNotNull();
        var result=crosswalk.enrich(List.of(
            row("BSE_CM","BSE","500325","RELIANCE",null),
            row("BSE_CM","BSE","500325","WRONG",null),
            row("BSE_CM","BSE","500326","OTHER",null),
            row("NSE_CM","NSE","500325","RELIANCE",null),
            row("BSE_CM","BSE","500325","RELIANCE","MF")
        ));
        assertThat(result.get(0).instrumentType()).isEqualTo("EQ");
        assertThat(result.get(1).instrumentType()).isNull();
        assertThat(result.get(2).instrumentType()).isNull();
        assertThat(result.get(3).instrumentType()).isNull();
        assertThat(result.get(4).instrumentType()).isEqualTo("MF");
    }

    @Test
    @Transactional
    void failingCompletenessGatePreservesPreviouslyPublishedVersion() {
        var original=new UpstoxBseCrosswalkParser.Parsed(500,120,380,verified(120));
        var before=crosswalk.publish(original,"f00df00df00df00d".repeat(4),Instant.now());
        var truncated=new UpstoxBseCrosswalkParser.Parsed(100,99,1,verified(99));
        assertThatThrownBy(() -> crosswalk.publish(truncated,
            "0123456789abcdef".repeat(4),Instant.now().plusSeconds(1)))
            .isInstanceOf(InstrumentMasterException.class);
        assertThat(crosswalk.current().version()).isEqualTo(before.version());
    }

    private static Map<String,UpstoxBseCrosswalkParser.Equity> verified(int count) {
        var found=new java.util.HashMap<String,UpstoxBseCrosswalkParser.Equity>();
        for (int i=0;i<count;i++) {
            String code=Integer.toString(510000+i);
            String isin="INE000A01" + String.format("%03d",i);
            found.put(code,new UpstoxBseCrosswalkParser.Equity(
                code,"TEST"+i,isin,"BSE_EQ|"+isin));
        }
        found.put("500325",new UpstoxBseCrosswalkParser.Equity(
            "500325","RELIANCE","INE002A01018","BSE_EQ|INE002A01018"));
        if (count==99) found.remove("510000");
        return Map.copyOf(found);
    }

    private static InstrumentMasterParser.InstrumentDraft row(
            String segment,String exchange,String token,String symbol,String type) {
        return new InstrumentMasterParser.InstrumentDraft(segment,exchange,token,symbol,
            symbol,type,null,null,1,new BigDecimal("0.05"));
    }
}
