package com.example.ApexPay;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Actuator health probe wiring for orchestrated (Kubernetes-style)
 * deployment, and that endpoint exposure is minimized.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorHealthTests {

	@Autowired
	private TestRestTemplate restTemplate;

	@Test
	void healthEndpointIsUp() {
		ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("UP");
	}

	@Test
	void livenessProbeIsUp() {
		ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health/liveness", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void readinessProbeIsUp() {
		ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health/readiness", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void envEndpointIsNotExposed() {
		ResponseEntity<String> response = restTemplate.getForEntity("/actuator/env", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}
}
