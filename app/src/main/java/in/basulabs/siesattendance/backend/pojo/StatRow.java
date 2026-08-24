package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import in.basulabs.siesattendance.backend.stats.StatsGroupBy;
import in.basulabs.siesattendance.backend.util.NameFormat;

/**
 * One row of a grouped statistics report.
 * <p>
 * What a row represents depends entirely on the grouping that produced it: a student, a course,
 * a month, a weekday. The labels are therefore generic, and the grouping has to be consulted
 * before either of them can be rendered, which is what the {@code display} methods below are
 * for.
 * <p>
 * All four counts are populated for every grouping, though not all of them are meaningful in
 * each. Grouping by student makes {@code students} one by definition; grouping by lecture does
 * the same to {@code lectures}.
 */
public class StatRow {

	/**
	 * Primary key of the subject when the grouping has one, otherwise 0. Only the groupings for
	 * which {@code StatsGroupBy.isDrillable()} holds put a usable key here.
	 */
	public long rowId;

	/** The row's heading. For a student this is the roll number, not the name. */
	@NonNull
	public String primaryLabel = "";

	/** Supporting text beneath the heading, empty when the grouping has nothing to put there. */
	@NonNull
	public String secondaryLabel = "";

	/** Attendance records marked present. */
	public int attended;

	/** Attendance records in total, present or absent. */
	public int total;

	/** Distinct lectures contributing to this row. */
	public int lectures;

	/** Distinct students contributing to this row. */
	public int students;

	public StatRow() {
	}

	public double percentage() {
		return total == 0 ? 0d : (attended * 100d) / total;
	}

	/**
	 * Whether this row falls short of the threshold. A row with no records at all is never
	 * counted as short, since nought per cent of nothing is not a shortfall.
	 */
	public boolean isBelow(double threshold) {
		return total > 0 && percentage() < threshold;
	}

	/**
	 * Names arrive raw from the roster, so whichever label holds one is case-corrected for
	 * display. Which label that is depends on the grouping: for students the name is the
	 * secondary label, for professors the primary. So the grouping is asked rather than assumed,
	 * and a grouping whose labels are not names leaves both untouched.
	 */
	@NonNull
	public String displayPrimary(@NonNull StatsGroupBy.NameField nameField) {
		return nameField == StatsGroupBy.NameField.PRIMARY
				? NameFormat.properCase(primaryLabel)
				: primaryLabel;
	}

	/**
	 * The supporting label, or null when the grouping has none. Null rather than an empty
	 * string, so the view can be hidden outright instead of collapsing to a blank line.
	 */
	@Nullable
	public String displaySecondary(@NonNull StatsGroupBy.NameField nameField) {
		if (secondaryLabel.isEmpty()) {
			return null;
		}
		return nameField == StatsGroupBy.NameField.SECONDARY
				? NameFormat.properCase(secondaryLabel)
				: secondaryLabel;
	}
}
