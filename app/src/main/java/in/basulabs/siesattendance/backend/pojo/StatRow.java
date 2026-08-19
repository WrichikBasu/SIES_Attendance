package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import in.basulabs.siesattendance.backend.stats.StatsGroupBy;
import in.basulabs.siesattendance.backend.util.NameFormat;

/**
 * One row of a grouped statistics report. What the row represents depends on the grouping that
 * produced it — a student, a course, a month, a weekday — so the labels are generic.
 */
public class StatRow {

	/** Primary key of the subject when the grouping has one, otherwise 0. */
	public long rowId;

	@NonNull
	public String primaryLabel = "";

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

	public boolean isBelow(double threshold) {
		return total > 0 && percentage() < threshold;
	}

	/**
	 * Names arrive raw from the roster, so whichever label holds one is case-corrected for
	 * display. Which label that is depends on the grouping — for students the name is the
	 * secondary label, for professors the primary — so the grouping is asked rather than
	 * assumed.
	 */
	@NonNull
	public String displayPrimary(@NonNull StatsGroupBy.NameField nameField) {
		return nameField == StatsGroupBy.NameField.PRIMARY
				? NameFormat.properCase(primaryLabel)
				: primaryLabel;
	}

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
