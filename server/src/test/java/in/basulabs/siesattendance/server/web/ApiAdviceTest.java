package in.basulabs.siesattendance.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** The catch-all keeps an unexpected exception in the API's one error shape. */
class ApiAdviceTest {

	private final ApiAdvice advice = new ApiAdvice(new LoginRateLimiter());

	@Test
	void anUnexpectedExceptionIsAServerErrorInTheUsualShape() {

		ResponseEntity<Map<String, Object>> response =
				advice.onUnexpected(new ClassCastException("java.lang.String secret detail"));

		assertEquals(500, response.getStatusCode().value());
		assertEquals("server_error", response.getBody().get("code"));
		assertEquals(Map.of("code", "server_error",
				"message", "Something went wrong at the server."), response.getBody());
		assertFalse(response.getBody().toString().contains("secret detail"),
				"the exception's message must not reach the client");
	}

	@Test
	void anExceptionThatCarriesAClientStatusKeepsIt() {

		ResponseEntity<Map<String, Object>> response = advice.onUnexpected(
				new ResponseStatusException(HttpStatus.NOT_FOUND, "no such thing"));

		assertEquals(404, response.getStatusCode().value());
		assertEquals("request_refused", response.getBody().get("code"));
	}

	@Test
	void anExceptionThatCarriesAServerStatusIsStillAServerError() {

		ResponseEntity<Map<String, Object>> response = advice.onUnexpected(
				new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream"));

		assertEquals(500, response.getStatusCode().value());
		assertEquals("server_error", response.getBody().get("code"));
	}
}
