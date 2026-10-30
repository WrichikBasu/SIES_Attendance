package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import in.basulabs.siesattendance.server.importer.Progress;

import jakarta.servlet.http.HttpServletRequest;

/**
 * How far a long request has got, asked for while it runs.
 * <pre>
 * GET /api/v1/progress/{id}    {"stage": "…", "done": n, "total": n}
 * </pre>
 * The page makes up an id, sends it as {@value #HEADER} on the long request, and asks here
 * with it until that request answers. Only the account that started the request can ask, and
 * an id nobody has started, or one already finished, is a 404 the page treats as "not yet".
 * <p>
 * In memory, because there is one server and a request's progress means nothing once it ends.
 */
@RestController
@RequestMapping(Api.V1 + "/progress")
public final class ProgressController {

	/** The header a long request's progress id arrives in. */
	public static final String HEADER = "X-Progress-Id";

	public record ProgressBody(@NonNull String stage, long done, long total) {
	}

	/** The requests running now that said they want to be watched. */
	@Component
	public static final class Board {

		/** Long enough for any import; an entry outliving it was leaked by a crash. */
		private static final Duration STALE = Duration.ofHours(2);

		private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

		private record Entry(long accountId, @NonNull Progress progress,
		                     @NonNull Instant started) {
		}

		private final Map<String, Entry> running = new ConcurrentHashMap<>();

		/**
		 * A progress for one request: watched when {@code id} is a usable id, and otherwise
		 * one nobody reads. {@link #finish} it when the request ends.
		 */
		@NonNull
		public Progress start(@Nullable String id, long accountId) {

			Progress progress = new Progress();

			if (id != null && ID.matcher(id).matches()) {
				Instant now = Instant.now();
				running.values().removeIf(entry -> entry.started().plus(STALE).isBefore(now));
				running.put(id, new Entry(accountId, progress, now));
			}
			return progress;
		}

		public void finish(@Nullable String id) {
			if (id != null) {
				running.remove(id);
			}
		}

		@Nullable
		Progress.State read(@NonNull String id, long accountId) {
			Entry entry = running.get(id);
			return entry == null || entry.accountId() != accountId
					? null : entry.progress().state();
		}
	}

	@NonNull
	private final Board board;

	public ProgressController(@NonNull Board board) {
		this.board = board;
	}

	@GetMapping("/{id}")
	@NonNull
	public ProgressBody progress(@PathVariable("id") @NonNull String id,
	                             @NonNull HttpServletRequest http) {

		Progress.State state = board.read(id, RequestActor.of(http).accountId());

		if (state == null) {
			throw new ApiException(404, "no_such_progress",
					"Nothing of yours is running under that id.");
		}
		return new ProgressBody(state.stage(), state.done(), state.total());
	}
}
