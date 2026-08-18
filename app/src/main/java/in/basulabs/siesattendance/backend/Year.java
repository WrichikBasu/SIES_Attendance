package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public enum Year {

	FY("FY"),
	SY("SY"),
	TY("TY"),
	Masters_P1("Masters Part 1"),
	Masters_P2("Masters Part 2");

	@NonNull
	private final String label;

	Year(@NonNull String label) {
		this.label = label;
	}

	@NonNull
	@Override
	public String toString() {
		return label;
	}

	/** True for the two postgraduate years. */
	public boolean isMasters() {
		return this == Masters_P1 || this == Masters_P2;
	}

	@Nullable
	public static Year fromAny(@Nullable String raw) {
		return EnumLookup.find(Year.class, raw);
	}
}
