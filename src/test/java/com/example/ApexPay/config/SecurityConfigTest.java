package com.example.ApexPay.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** The app must refuse to start on missing credentials rather than use well-known ones. */
class SecurityConfigTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SecurityAutoConfiguration.class, WebMvcAutoConfiguration.class))
            .withUserConfiguration(SecurityConfig.class);

    @Test
    void startsWhenBothPasswordsAreSet() {
        runner.withPropertyValues("API_PASSWORD=a-secret", "ACTUATOR_PASSWORD=b-secret")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesToStartWithoutApiPassword() {
        runner.withPropertyValues("ACTUATOR_PASSWORD=b-secret")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("API_PASSWORD"));
    }

    @Test
    void refusesToStartWithBlankActuatorPassword() {
        runner.withPropertyValues("API_PASSWORD=a-secret", "ACTUATOR_PASSWORD=")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("ACTUATOR_PASSWORD"));
    }
}
