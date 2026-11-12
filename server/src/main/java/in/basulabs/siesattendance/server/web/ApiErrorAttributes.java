package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.web.context.request.WebRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one error shape, for the refusals that never reach a controller.
 * <p>
 * {@link ApiAdvice} puts {@code {"code", "message"}} on everything thrown out of a handler, but
 * a wrong method, a body in a type nothing here reads, or an address with no handler at all is
 * refused by the framework before any handler is chosen. Those left as Spring Boot's own error
 * body — {@code timestamp}, {@code status}, {@code error}, {@code path} — so a client reading
 * {@code code} to decide what to do found no {@code code} exactly when something was wrong, and
 * had to tell a 405 from a 401 by the status alone or by parsing a different shape.
 * <p>
 * Replacing the attributes rather than adding a handler for each of those exceptions: this is
 * the one place every one of them passes through, including the ones a filter or the container
 * raises, where a {@code @ControllerAdvice} never runs.
 * <p>
 * The headers the framework sets with the status — {@code Allow} on a 405, {@code Accept} on a
 * 415 — are set on the response before this is asked for a body, so they survive. Nothing here
 * touches them.
 * <p>
 * The message says what to do about it and no more. In particular it does not name the path:
 * the request came with it, so repeating it tells the caller nothing, and an error body that
 * quotes the request back is the shape that ends up reflecting a URL into a page.
 *
 * @see ApiAdvice for everything thrown out of a controller
 */
public final class ApiErrorAttributes extends DefaultErrorAttributes {

	/**
	 * What the client branches on, by status.
	 * <p>
	 * Only the ones a correct client can actually provoke, and the wording matches
	 * {@link ApiAdvice}'s for the statuses both can produce. Everything else falls to
	 * {@code request_refused} or, at 5xx, to {@code server_error} — the same two sentences
	 * {@code ApiAdvice} ends on, so that a client cannot tell from the body whether a refusal
	 * came from the framework or from here, which is no business of its.
	 */
	private static final Map<Integer, String[]> BY_STATUS = Map.of(
			400, new String[]{"bad_request", "That request could not be read."},
			404, new String[]{"not_found", "There is nothing at that address."},
			405, new String[]{"method_not_allowed",
					"That address does not answer that method."},
			406, new String[]{"not_acceptable",
					"That address cannot answer in a form this request accepts."},
			413, new String[]{"too_large", "That request is too large."},
			415, new String[]{"unsupported_media_type",
					"That address does not take a body of that kind."});

	private static final String[] REFUSED =
			{"request_refused", "The server would not take that request."};

	private static final String[] SERVER_ERROR =
			{"server_error", "Something went wrong at the server."};

	@Override
	@NonNull
	public Map<String, Object> getErrorAttributes(@NonNull WebRequest request,
	                                              @NonNull ErrorAttributeOptions options) {

		// Asked for in full, and then not used: the superclass is what records the error and
		// the status for the request in the first place, and the status is the only part of its
		// answer that goes any further.
		Map<String, Object> framework = super.getErrorAttributes(request, options);

		Object status = framework.get("status");
		int code = status instanceof Integer number ? number : 500;

		String[] refusal = BY_STATUS.get(code);

		if (refusal == null) {
			refusal = code >= 500 ? SERVER_ERROR : REFUSED;
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", refusal[0]);
		body.put("message", refusal[1]);
		return body;
	}
}
