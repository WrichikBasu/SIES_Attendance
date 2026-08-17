package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;

public enum CourseType {

	MAJOR,
	MINOR,
	MAJOR_PRACTICALS,
	MINOR_PRACTICALS,
	OE,
	AEC,
	IKS;

	@NonNull
	public String toString() {

		if (name().equals("MAJOR") | name().equals("MINOR"))
			return name().charAt(0) + name().substring(1).toLowerCase();

		else if (name().equals("MINOR_PRACTICALS") | name().equals("MAJOR_PRACTICALS"))
			return name().charAt(0) + name().substring(1, name().indexOf('_')).toLowerCase() + " (Prac)";

		else
			return name();
	}

}
