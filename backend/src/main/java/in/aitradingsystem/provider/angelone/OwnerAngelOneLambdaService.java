package in.aitradingsystem.provider.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * Dedicated owner-only Angel One account reads through the existing private SSM
 * market-feed Lambda. EC2 and Flutter never fetch or hold the four credentials.
 * The backend binds only a masked verified account to the Cognito customer UUID.
 * Broker JWT stays within Lambda memory, expires there, and is never returned.
 */
@Service
public final class OwnerAngelOneLambdaService {
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final int MAX_REPLY = 115_000;
    private final ObjectProvider<LambdaClient> clients;
    private final ObjectMapper mapper;
    private final String provider;
    private final String functionName;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    private record Session(String masked, Instant expiresAt) {}

    @Autowired
    public OwnerAngelOneLambdaService(ObjectProvider<LambdaClient> clients,
            ObjectMapper mapper,
            @Value("${app.market-data.provider:ANGEL_ONE}") String provider,
            @Value("${app.market-data.lambda-function-name:}") String functionName) {
        this(clients, mapper, provider, functionName, Clock.systemUTC());
    }

    OwnerAngelOneLambdaService(ObjectProvider<LambdaClient> clients, ObjectMapper mapper,
            String provider, String functionName, Clock clock) {
        this.clients = clients;
        this.mapper = mapper;
        this.provider = provider;
        this.functionName = functionName;
        this.clock = clock;
    }

    private boolean configured() {
        return "ANGEL_ONE_LAMBDA".equals(provider)
            && "minttick-dev-angelone-feed".equals(functionName)
            && clients.getIfAvailable() != null;
    }

    public boolean available() {
        if (!configured()) return false;
        try {
            JsonNode result = invoke(Map.of("operation", "status"));
            return result.path("configured").asBoolean(false);
        } catch (AngelOneException ex) {
            return false;
        }
    }

    public CustomerAngelOneSessionService.ConnectionStatus connect(UUID customer) {
        JsonNode result = invoke(Map.of("operation", "broker-connect"));
        String masked = result.path("clientCodeMasked").asText("");
        if (!masked.matches("\\*{3}[A-Za-z0-9]{1,3}")
                || !"ANGEL_ONE".equals(result.path("broker").asText())
                || result.path("orderExecutionEnabled").asBoolean(true)) {
            throw error(AngelOneException.Kind.INVALID_RESPONSE);
        }
        Instant now = clock.instant();
        Instant midnight = ZonedDateTime.ofInstant(now, INDIA).toLocalDate()
            .plusDays(1).atStartOfDay(INDIA).toInstant();
        Instant expires = now.plusSeconds(3 * 3600);
        if (midnight.isBefore(expires)) expires = midnight;
        sessions.put(customer, new Session(masked, expires));
        return status(customer);
    }

    public CustomerAngelOneSessionService.ConnectionStatus status(UUID customer) {
        Session s = sessions.get(customer);
        if (s != null && !clock.instant().isBefore(s.expiresAt())) {
            sessions.remove(customer, s);
            s = null;
        }
        return s == null
            ? new CustomerAngelOneSessionService.ConnectionStatus(false, "ANGEL_ONE", null, null, false)
            : new CustomerAngelOneSessionService.ConnectionStatus(true, "ANGEL_ONE", s.masked(), s.expiresAt(), false);
    }

    public void disconnect(UUID customer) {
        sessions.remove(customer);
    }

    public CustomerAngelOneSessionService.BrokerRead read(UUID customer, String resource) {
        if (!status(customer).connected()) {
            throw new CustomerAngelOneSessionService.BrokerConnectionRequiredException();
        }
        if (!java.util.Set.of("funds", "holdings", "positions", "orders").contains(resource)) {
            throw error(AngelOneException.Kind.REJECTED);
        }
        final JsonNode response;
        try {
            response = invoke(Map.of("operation", "broker-read", "resource", resource));
        } catch (AngelOneException ex) {
            if (ex.kind() == AngelOneException.Kind.SESSION_EXPIRED) disconnect(customer);
            throw ex;
        }
        JsonNode data = response.get("data");
        if (data == null || !(data.isObject() || data.isArray())
                || !"ANGEL_ONE".equals(response.path("provider").asText())) {
            throw error(AngelOneException.Kind.INVALID_RESPONSE);
        }
        return new CustomerAngelOneSessionService.BrokerRead(
            "ANGEL_ONE", clock.instant(), data);
    }

    private JsonNode invoke(Map<String, String> operation) {
        if (!configured()) throw error(AngelOneException.Kind.NOT_CONFIGURED);
        try {
            byte[] input = mapper.writeValueAsBytes(operation);
            InvokeResponse response = clients.getObject().invoke(
                InvokeRequest.builder().functionName(functionName)
                    .invocationType(InvocationType.REQUEST_RESPONSE)
                    .payload(SdkBytes.fromByteArray(input)).build());
            if (response.statusCode() == null || response.statusCode() != 200
                    || response.functionError() != null || response.payload() == null) {
                throw error(AngelOneException.Kind.UNAVAILABLE);
            }
            byte[] output = response.payload().asByteArray();
            if (output.length > MAX_REPLY) throw error(AngelOneException.Kind.INVALID_RESPONSE);
            JsonNode result = mapper.readTree(output);
            if (!result.isObject() || !result.path("ok").asBoolean(false)) {
                String code = result != null && result.isObject()
                    ? result.path("code").asText("") : "";
                if ("SESSION_EXPIRED".equals(code)) throw error(AngelOneException.Kind.SESSION_EXPIRED);
                if ("CREDENTIALS_NOT_CONFIGURED".equals(code) || "INVALID_TOTP_CONFIGURATION".equals(code))
                    throw error(AngelOneException.Kind.NOT_CONFIGURED);
                if ("PROVIDER_RATE_LIMITED".equals(code)) throw error(AngelOneException.Kind.RATE_LIMITED);
                if ("PROVIDER_REJECTED".equals(code) || "BROKER_ACCOUNT_MISMATCH".equals(code))
                    throw error(AngelOneException.Kind.REJECTED);
                throw error(AngelOneException.Kind.UNAVAILABLE);
            }
            return result;
        } catch (AngelOneException ex) {
            throw ex;
        } catch (SdkException | java.io.IOException | RuntimeException ex) {
            // Never log the raw Lambda response, request, AWS exception or account secret.
            throw error(AngelOneException.Kind.UNAVAILABLE);
        }
    }

    private static AngelOneException error(AngelOneException.Kind kind) {
        return new AngelOneException(kind, "BROKER_READ_ONLY_UNAVAILABLE",
            "Secure read-only Angel One connection is unavailable");
    }
}
