package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.basulabs.siesattendance.backend.util.AttendanceCsvParser;
import in.basulabs.siesattendance.backend.util.RosterCsv;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The two CSV formats, described for a person, with an example of each.
 * <p>
 * The text is the shared module's, the same the app's format dialogs show, so the console and
 * the phone cannot describe a format differently -- and both are built from the rules the
 * parsers enforce.
 */
@RestController
@RequestMapping(Api.V1 + "/import/formats")
public final class ImportFormatsController {

	public record Formats(@NonNull String students, @NonNull String studentsExample,
	                      @NonNull String attendance, @NonNull String attendanceExample) {
	}

	/**
	 * The actor is read and not used, which is the point of reading it.
	 * <p>
	 * There is nothing here worth protecting — it is the same text the app's own dialogs show,
	 * and the README prints it — but this was the one endpoint in the service that would answer
	 * without an actor, and so the one a mistake in {@link BearerTokenFilter} could be seen
	 * through. It was seen through: an encoded path skipped the filter, and every other
	 * controller answered 401 from {@link RequestActor#of} while this one answered 200. The
	 * filter refuses by default now; this is the second lock, and it costs a line.
	 */
	@GetMapping
	@NonNull
	public Formats formats(@NonNull HttpServletRequest http) {

		RequestActor.of(http);

		return new Formats(RosterCsv.formatHelp(), RosterCsv.templateCsv(),
				AttendanceCsvParser.formatHelp(), AttendanceCsvParser.templateCsv());
	}
}
