package com.example.ApexPay.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Locks down the payment API and sensitive Actuator endpoints while keeping the
 * orchestrator/Kubernetes health probes reachable anonymously.
 *
 * <p>Authentication is HTTP Basic: this is a stateless service-to-service REST API
 * (and the shape our tests exercise), so there are no browser sessions or cookies.
 * For that reason CSRF protection is disabled and session management is STATELESS
 * -- CSRF only defends cookie/session-backed browser flows, which this API does not
 * have; every request re-authenticates via its Authorization header.
 *
 * <p>Credentials are supplied via environment variables with local/test-only
 * defaults (see .env.example). Plaintext from the environment is BCrypt-encoded at
 * startup; no password hashes are committed and passwords are never logged.
 * <b>Production MUST override the {@code API_*}/{@code ACTUATOR_*} defaults.</b>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final String apiUser;
    private final String apiPassword;
    private final String actuatorUser;
    private final String actuatorPassword;

    public SecurityConfig(
            @Value("${API_USER:apiuser}") String apiUser,
            @Value("${API_PASSWORD:changeit}") String apiPassword,
            @Value("${ACTUATOR_USER:ops}") String actuatorUser,
            @Value("${ACTUATOR_PASSWORD:changeit}") String actuatorPassword) {
        this.apiUser = apiUser;
        this.apiPassword = apiPassword;
        this.actuatorUser = actuatorUser;
        this.actuatorPassword = actuatorPassword;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // Orchestrator / K8s probes must be reachable without credentials.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Ops/observability surface: Prometheus scrape + metrics need the ops role.
                        .requestMatchers("/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**")
                        .hasRole("ACTUATOR")
                        // Any other actuator endpoint that ever gets exposed is ops-only too.
                        .requestMatchers("/actuator/**").hasRole("ACTUATOR")
                        // Payment API requires an authenticated service user.
                        .requestMatchers("/api/v1/**").hasRole("USER")
                        // Deny-by-default: everything else must be authenticated.
                        .anyRequest().authenticated())
                // Stateless REST/service API secured by Basic auth: no browser session or
                // CSRF token exchange, so CSRF is disabled and no HTTP session is created.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults())
                // No browser form login for a headless service API.
                .formLogin(form -> form.disable());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * In-memory users seeded from environment variables (BCrypt-encoded at startup).
     * The ops user also holds ROLE_USER so a single ops credential can hit both the
     * observability endpoints and the payment API if needed.
     */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        UserDetails api = User.withUsername(apiUser)
                .password(passwordEncoder.encode(apiPassword))
                .roles("USER")
                .build();
        UserDetails ops = User.withUsername(actuatorUser)
                .password(passwordEncoder.encode(actuatorPassword))
                .roles("ACTUATOR", "USER")
                .build();
        return new InMemoryUserDetailsManager(api, ops);
    }
}
