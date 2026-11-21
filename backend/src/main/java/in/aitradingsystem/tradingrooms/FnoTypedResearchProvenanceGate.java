package in.aitradingsystem.tradingrooms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;

/**
 * Shared defensive boundary for original STR-203..212 typed JSON envelopes.
 *
 * Source database receipts bind one provider and envelope first-known time.
 * This guard separately verifies the ENTIRE nested payload cannot switch
 * providers or contain observations first-known only after the envelope.
 * Model-specific instrument, Greeks, positions and quote validations remain
 * mandatory in the ten distinct strategy evaluators.
 *
 * Read-only; never grants market data rights or trading permissions.
 */
final class FnoTypedResearchProvenanceGate {
    private static final int MAX_DEPTH=32;
    private static final int MAX_NODES=50000;
    private static final int MAX_BYTES=1000000;

    private FnoTypedResearchProvenanceGate() {}

    static boolean valid(ObjectMapper mapper,String json,String provider,
                         Instant envelopeFirstKnownAt,Instant asOf) {
        if(mapper==null||json==null||json.isBlank()
                ||json.length()>MAX_BYTES
                ||envelopeFirstKnownAt==null||asOf==null
                ||envelopeFirstKnownAt.isAfter(asOf)
                ||!("UPSTOX_ANALYTICS".equals(provider)
                   ||"ANGEL_ONE_SMARTAPI".equals(provider)))
            return false;
        try {
            JsonNode root=mapper.readTree(json);
            if(root==null||!root.isObject())return false;
            Counters counters=new Counters();
            if(!verify(root,provider,envelopeFirstKnownAt,0,counters))
                return false;
            return counters.providers>0 && counters.timestamps>0;
        }catch(IOException | IllegalArgumentException invalid){
            return false;
        }
    }

    private static boolean verify(JsonNode node,String provider,Instant envelopeKnown,
                                  int depth,Counters counter) {
        if(node==null||depth>MAX_DEPTH||++counter.nodes>MAX_NODES)return false;
        if(node.isArray()){
            for(JsonNode child:node){
                if(!verify(child,provider,envelopeKnown,depth+1,counter))return false;
            }
            return true;
        }
        if(!node.isObject())return true;
        Iterator<java.util.Map.Entry<String,JsonNode>> fields=node.fields();
        while(fields.hasNext()){
            var field=fields.next();
            var key=field.getKey();
            var value=field.getValue();
            if("provider".equals(key)){
                counter.providers++;
                if(value==null||!value.isTextual()
                        ||!provider.equals(value.textValue()))return false;
            }
            if(key.endsWith("FirstKnownAt")||"firstKnownAt".equals(key)){
                counter.timestamps++;
                if(value==null||!value.isTextual())return false;
                final Instant witnessed;
                try {
                    witnessed=Instant.parse(value.textValue());
                } catch(DateTimeParseException invalid){
                    return false;
                }
                if(witnessed.isAfter(envelopeKnown))return false;
            }
            if(!verify(value,provider,envelopeKnown,depth+1,counter))
                return false;
        }
        return true;
    }

    private static final class Counters {
        int providers;
        int timestamps;
        int nodes;
    }
}
