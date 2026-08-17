package in.basulabs.siesattendance.backend.pojo;

/**
 * Headline figures for whatever a statistics filter currently selects.
 */
public class StatsSummary {

	public int attended;

	public int total;

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

	public boolean isEmpty() {
		return total == 0;
	}
}
