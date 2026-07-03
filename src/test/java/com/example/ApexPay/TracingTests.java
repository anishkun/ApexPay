package com.example.ApexPay;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Micrometer Tracing + OpenTelemetry bridge wiring. Broker-free and
 * collector-free.
 *
 * <p>The explicit {@code management.otlp.tracing.export.enabled=false} mirrors main's
 * {@code ${TRACING_EXPORT_ENABLED:false}} default and pins the contract that the app
 * starts WITHOUT a collector: Boot skips the OTLP exporter when export is disabled.
 * (An empty endpoint is NOT a safe off-switch -- it fails startup with
 * "Invalid endpoint, must start with http:// or https://".)
 *
 * <p>{@code @AutoConfigureObservability} re-enables tracing (disabled by default in
 * tests) so the Tracer bean and MDC propagation can be asserted.
 */
@SpringBootTest(properties = "management.otlp.tracing.export.enabled=false")
@AutoConfigureObservability
class TracingTests {

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private Tracer tracer;

    @Test
    void contextLoadsWithTracingWiredAndNoOtlpEndpoint() {
        // Context startup itself is the assertion: tracing deps on classpath,
        // export disabled, no collector running.
        assertThat(observationRegistry).isNotNull();
    }

    @Test
    void tracerBeanIsPresent() {
        assertThat(tracer).isNotNull();
        assertThat(tracer.currentSpan()).as("no ambient span outside any scope").isNull();
    }

    @Test
    void observationExposesTraceIdViaTracerAndMdc() {
        Observation observation = Observation.start("apexpay.test.trace", observationRegistry);
        try (Observation.Scope scope = observation.openScope()) {
            // The observation is bridged to an OTel span...
            assertThat(tracer.currentSpan()).isNotNull();
            String traceId = tracer.currentSpan().context().traceId();
            assertThat(traceId).isNotBlank();

            // ...and the bridge's Slf4jEventListener mirrors it into the MDC, which the
            // ECS structured console logs render on every log line inside the span.
            assertThat(MDC.get("traceId")).isEqualTo(traceId);
            assertThat(MDC.get("spanId")).isNotBlank();
        } finally {
            observation.stop();
        }

        // Scope closed: trace fields must not leak onto the thread.
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("spanId")).isNull();
    }
}
