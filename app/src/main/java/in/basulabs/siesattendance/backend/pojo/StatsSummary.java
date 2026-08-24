package in.basulabs.siesattendance.backend.pojo;

/**
 * Headline figures for whatever a statistics filter currently selects.
 * <p>
 * Produced by the same WHERE clause as the grouped report, without the grouping, so the totals
 * here always agree with the rows on screen. The distinct counts are the reason this is a
 * separate query rather than a sum over the rows: summing {@code students} across per-course
 * rows would count a student once per course they take.
 */
public class StatsSummary {

	/** Attendance records marked present. */
	public int attended;

	/** Attendance records in total, present or absent. */
	public int total;

	/** Distinct lectures, students and courses contributing to the figures above. */
	public int lectures;

	public int students;

	public int courses;

	public StatsSummary() {
	}

	public double percentage() {
		return total == 0 ? 0d : (attended * 100d) / total;
	}

	public int absent() {
		return total - attended;
	}

	/**
	 * True when the filter matched nothing at all. Worth distinguishing from a genuine nought
	 * per cent, which the screen presents very differently.
	 */
	public boolean isEmpty() {
		return total == 0;
	}
}
