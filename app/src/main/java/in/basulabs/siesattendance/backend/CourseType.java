package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public enum CourseType {

	MAJOR("Major"),
	MINOR("Minor"),
	MAJOR_PRACTICALS("Major practicals"),
	MINOR_PRACTICALS("Minor practicals"),
	OE("Open elective"),
	AEC("AEC"),
	IKS("IKS");

	@NonNull
	private final String label;

	CourseType(@NonNull String label) {
		this.label = label;
	}

	@NonNull
	@Override
	public String toString() {
		return label;
	}

	@Nullable
	public static CourseType fromAny(@Nullable String raw) {
		return EnumLookup.find(CourseType.class, raw);
	}
}
