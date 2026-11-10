package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The headers every response carries, whatever produced it.
 * <p>
 * Set before the request reaches anything else, so that a refusal written by a filter, a body
 * written by a controller and an error page assembled by the framework all leave with them. A
 * handler that needs to say something different still can: these are set with
 * {@code setHeader} on the way in, and whoever writes the response afterwards overwrites them.
 *
 * <h3>What each one is for</h3>
 * <ul>
 * <li>{@code X-Content-Type-Options: nosniff} — a browser must believe the declared type. The
 * console is the only page here, but an uploaded CSV handed back by an export endpoint is
 * exactly the kind of body a sniffing browser might decide is HTML.</li>
 * <li>{@code Referrer-Policy: no-referrer} — nothing on these pages links out, and a URL under
 * {@code /api/v1} names a student or a lecture by key.</li>
 * <li>{@link #POLICY} — see below.</li>
 * <li>{@code Cache-Control: no-store} on {@code /api/*} — every answer under there is somebody's
 * attendance, roll or account, answered for one session's token. A shared cache or a browser's
 * back-forward store keeping it is a copy nobody signed in for. The pages under {@code static/}
 * are left alone: they are revalidated instead, which {@code application.yml} explains.</li>
 * </ul>
 *
 * <h3>The content security policy</h3>
 * {@code default-src 'none'} and then only what the console actually uses: an inline stylesheet,
 * an inline script, and {@code fetch}/{@code XMLHttpRequest} to this origin. It loads nothing
 * from anywhere — no font, no image, no library — so the policy says so, and a page that starts
 * to is refused until somebody widens this on purpose.
 * <p>
 * {@code 'unsafe-inline'} is in it because the console is one self-contained file by design:
 * its style and script are inline so that the page cannot half-load from an address that is
 * itself broken. That is the weak part of this policy, and it is weak in one direction only —
 * an injected {@code <script>} would run, an injected {@code <script src>} would not, and
 * neither could send anything anywhere but here. Everything the console writes into the page
 * goes through {@code textContent}, which is what actually stops the first of those; this is
 * the net under it. Hashes instead of {@code 'unsafe-inline'} would be stronger and would have
 * to be recomputed every time that file is edited, with a blank console as the cost of
 * forgetting.
 */
public final class ResponseHeadersFilter extends OncePerRequestFilter {

	/** See the class notes. */
	private static final String POLICY = "default-src 'none'; script-src 'unsafe-inline'; "
			+ "style-src 'unsafe-inline'; connect-src 'self'; form-action 'none'; "
			+ "base-uri 'none'; frame-ancestors 'none'";

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request,
	                                @NonNull HttpServletResponse response,
	                                @NonNull FilterChain chain)
			throws ServletException, IOException {

		response.setHeader("X-Content-Type-Options", "nosniff");
		response.setHeader("Referrer-Policy", "no-referrer");
		response.setHeader("Content-Security-Policy", POLICY);

		if (isApi(request)) {
			response.setHeader("Cache-Control", "no-store");
		}

		chain.doFilter(request, response);
	}

	/**
	 * Whether this is a call to the API rather than a page or a probe.
	 * <p>
	 * The path as written, with the context path off the front, and a prefix test: unlike
	 * {@link BearerTokenFilter}, which must not let a spelling decide anything, the only thing
	 * riding on this is whether a response says not to store it. A path contrived to miss this
	 * test still goes through the token filter, which is mapped on the decoded path by the
	 * container.
	 */
	private static boolean isApi(@NonNull HttpServletRequest request) {

		String path = request.getRequestURI();
		String context = request.getContextPath();

		if (!context.isEmpty() && path.startsWith(context)) {
			path = path.substring(context.length());
		}

		return path.startsWith("/api/");
	}
}
