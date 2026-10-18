package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns an exception into a response body, in one place and one shape.
 * <p>
 * Every error the API returns is {@code {"code": ..., "message": ...}} — the code for the client
 * to branch on, the message for a person to read. Nothing else: no stack trace, no SQL, no
 * exception class name. A 500 that quotes the statement that failed is a 500 that tells an
 * attacker the shape of the schema, and the server's own log is where that belongs.
 */
@RestControllerAdvice
public final class ApiAdvice {

	/**
	 * PostgreSQL's {@code insufficient_privilege}, which is what a row-level security violation
	 * on an INSERT or UPDATE arrives as.
	 * <p>
	 * Worth distinguishing from every other database error: it is not a fault, it is the
	 * policies doing their job, and a 500 would send somebody looking for a bug instead of for
	 * the authority they do not have.
	 */
	private static final String INSUFFICIENT_PRIVILEGE = "42501";

	private static final Logger LOG = LoggerFactory.getLogger(ApiAdvice.class);

	@NonNull
	private final LoginRateLimiter limiter;

	/** Injected for one reason: {@code Retry-After} has to say the configured window. */
	public ApiAdvice(@NonNull LoginRateLimiter limiter) {
		this.limiter = limiter;
	}

	@ExceptionHandler(ApiException.class)
	@NonNull
	public ResponseEntity<Map<String, Object>> onApiException(@NonNull ApiException e) {

		ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());

		if (e.status() == HttpStatus.TOO_MANY_REQUESTS.value()) {
			response = response.header("Retry-After",
					Long.toString(limiter.window().toSeconds()));
		}

		return response.body(body(e.code(), e.getMessage()));
	}

	@ExceptionHandler(SQLException.class)
	@NonNull
	public ResponseEntity<Map<String, Object>> onSqlException(@NonNull SQLException e) {

		if (INSUFFICIENT_PRIVILEGE.equals(e.getSQLState())) {

			LOG.warn("a policy refused a statement: {}", e.getMessage());

			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(body("refused_by_database",
							"The database refused that: it is not yours to change."));
		}

		LOG.error("a database error reached the edge", e);

		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(body("server_error", "Something went wrong at the server."));
	}

	@ExceptionHandler({HttpMessageNotReadableException.class,
			MissingServletRequestParameterException.class,
			MethodArgumentTypeMismatchException.class})
	@NonNull
	public ResponseEntity<Map<String, Object>> onBadRequest(@NonNull Exception e) {

		// The exception's own message is not repeated: Spring's wording quotes the JSON it
		// could not parse, which is the caller's own body coming back at them and occasionally
		// a password.
		LOG.debug("a request could not be read", e);

		return ResponseEntity.badRequest()
				.body(body("bad_request", "That request could not be read."));
	}

	@NonNull
	private static Map<String, Object> body(@NonNull String code, @NonNull String message) {

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", code);
		body.put("message", message);
		return body;
	}
}
