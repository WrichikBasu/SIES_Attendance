package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * The designations a professor may hold, and the one that makes them head of their department.
 * <p>
 * A fixed list rather than free text, because one of them is an authority: an account created
 * for a professor whose designation is {@link #HOD} is made head of the professor's department
 * (see {@code AccountsController}). The phone's professor form and the console's both offer
 * exactly these, and the server refuses anything else for a professor it creates.
 */
public final class Designations {

	/** The designation that carries the head's role with it. */
	public static final String HOD = "HOD";

	/** Every designation, in rank order. */
	public static final List<String> ALL =
			List.of("Assistant Professor", "Associate Professor", "Professor", HOD);

	/** What was typed for a head before the list was fixed, and still on file for some. */
	private static final String HEAD_OF_DEPARTMENT = "Head of Department";

	private Designations() {
	}

	/** Whether a designation on file is a head's, in its current spelling or the old one. */
	public static boolean isHeads(@Nullable String designation) {
		return designation != null && (designation.trim().equalsIgnoreCase(HOD)
				|| designation.trim().equalsIgnoreCase(HEAD_OF_DEPARTMENT));
	}

	/**
	 * A designation on file, as one of {@link #ALL}: a head's old spelling read as {@link #HOD},
	 * any other listed one as it is. Null for one that is not on the list, such as a title
	 * typed before it was fixed, so a form asks for it again rather than guessing.
	 */
	@Nullable
	public static String normalise(@Nullable String designation) {

		if (isHeads(designation)) {
			return HOD;
		}

		for (String listed : ALL) {
			if (listed.equalsIgnoreCase(designation == null ? "" : designation.trim())) {
				return listed;
			}
		}
		return null;
	}

	/** Whether that is exactly one of {@link #ALL}. */
	public static boolean isListed(@NonNull String designation) {
		return ALL.contains(designation);
	}
}
