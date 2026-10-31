package in.aitradingsystem.security;

import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityHardeningFilter hardeningFilter,
            ApiKeyAuthenticationFilter apiKeyFilter,
            SessionAuthenticationFilter sessionFilter,
            SessionCsrfFilter sessionCsrfFilter,
            CorsConfigurationSource corsSource) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsSource))
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).preload(true).maxAgeInSeconds(31536000)))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/health", "/actuator/health/**", "/actuator/info", "/error",
                    "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("VIEWER", "TRADER", "ADMIN", "OPERATOR")
                .requestMatchers("/api/risk/**", "/api/execution-policy/**", "/api/users/**", "/api/admin/**")
                    .hasAnyRole("ADMIN", "OPERATOR")
                .requestMatchers("/api/paper/**", "/api/research/**", "/api/approvals/**", "/api/backtests/**")
                    .hasAnyRole("TRADER", "ADMIN", "OPERATOR")
                .requestMatchers("/api/auth/**").hasAnyRole("VIEWER", "TRADER", "ADMIN", "OPERATOR")
                .requestMatchers("/api/**").hasAnyRole("ADMIN", "OPERATOR")
                .anyRequest().denyAll())
            .addFilterBefore(hardeningFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(apiKeyFilter, SecurityHardeningFilter.class)
            .addFilterBefore(sessionFilter, ApiKeyAuthenticationFilter.class)
            .addFilterAfter(sessionCsrfFilter, ApiKeyAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsSource(@Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        List<String> normalizedOrigins = allowedOrigins.stream().map(String::trim).filter(s -> !s.isBlank()).toList();
        if (normalizedOrigins.isEmpty()) {
            throw new IllegalStateException("app.cors.allowed-origins must not be empty");
        }
        for (String origin : normalizedOrigins) {
            if (origin.contains("*")) {
                throw new IllegalStateException("Wildcard CORS origins are forbidden when credentials are enabled");
            }
            URI parsed = URI.create(origin);
            if (!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))
                    || parsed.getHost() == null || parsed.getPath() != null && !parsed.getPath().isEmpty()) {
                throw new IllegalStateException("Invalid CORS origin: " + origin);
            }
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(normalizedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
            "Content-Type", ApiKeyAuthenticationFilter.HEADER, SessionAuthenticationFilter.CSRF_HEADER,
            "Idempotency-Key", "X-Correlation-Id"));
        config.setExposedHeaders(List.of("X-Correlation-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
