package in.aitradingsystem.config;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Exact broker client network identity, never credentials or JWT headers. */
@Configuration
public class AngelOneClientConfig {
    private static final Pattern NUMERIC_IPV4 = Pattern.compile("[0-9.]{7,15}");
    private static final Pattern MAC = Pattern.compile("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}");

    @Bean
    RestClient angelOneRestClient(AngelOneProperties properties,
            @Value("${ANGELONE_CLIENT_LOCAL_IP:}") String localIp,
            @Value("${ANGELONE_CLIENT_PUBLIC_IP:}") String publicIp,
            @Value("${ANGELONE_CLIENT_MAC_ADDRESS:}") String mac) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeoutMs());
        factory.setReadTimeout(properties.readTimeoutMs());
        return withClientNetworkHeaders(RestClient.builder()
                .baseUrl(properties.baseUrl()).requestFactory(factory),
                localIp, publicIp, mac).build();
    }

    /**
     * SmartAPI documents these three headers on login, profile and broker reads.
     * Enforce complete validated host identity; never send placeholder fields.
     */
    static RestClient.Builder withClientNetworkHeaders(RestClient.Builder builder,
            String localIp, String publicIp, String mac) {
        if (isBlank(localIp) && isBlank(publicIp) && isBlank(mac)) return builder;
        if (!validIpv4(localIp) || !validIpv4(publicIp)
                || mac == null || !MAC.matcher(mac).matches()
                || "00:00:00:00:00:00".equals(mac)) {
            throw new IllegalArgumentException("Complete validated Angel One client network identity required");
        }
        return builder.defaultHeaders(headers -> {
            headers.set("X-ClientLocalIP", localIp);
            headers.set("X-ClientPublicIP", publicIp);
            headers.set("X-MACAddress", mac);
        });
    }

    private static boolean validIpv4(String value) {
        if (value == null || !NUMERIC_IPV4.matcher(value).matches()) return false;
        try {
            return InetAddress.getByName(value) instanceof Inet4Address;
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
