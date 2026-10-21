package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import in.basulabs.siesattendance.backend.util.AttendanceCsvParser;
import in.basulabs.siesattendance.backend.util.RosterCsv;

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

	@GetMapping
	@NonNull
	public Formats formats() {
		return new Formats(RosterCsv.formatHelp(), RosterCsv.templateCsv(),
				AttendanceCsvParser.formatHelp(), AttendanceCsvParser.templateCsv());
	}
}
