package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;

public enum Stream {

	BA,
	BSc,
	BSc_unaided,
	BCom,
	BMS,
	BAMMC,

	MA,
	MSc,
	MSc_unaided;

	@NonNull
	@Override
	public String toString() {
		if (name().contains("_unaided"))
			return name().substring(0, name().indexOf("_unaided"));
		else
			return name();
	}
}
