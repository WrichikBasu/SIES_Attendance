package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A teaching department.
 * <p>
 * {@link #name()} is what gets stored and what SQL compares against, and must never change.
 * {@link #toString()} is the human label and is the only thing that should ever be shown.
 */
public enum Department {

	// Sciences. Whether a course is aided is a property of the stream rather than of the
	// department: several of these run an aided BSc alongside an unaided MSc, and FTN is a
	// master's course only. AcademicRules holds which streams each one offers.
	PHYSICS("Physics"),
	CHEMISTRY("Chemistry"),
	MATHEMATICS("Mathematics"),
	BOTANY("Botany"),
	ZOOLOGY("Zoology"),
	BIOCHEMISTRY("Biochemistry"),
	STATISTICS("Statistics"),
	BIOANALYTICAL_SCIENCES("Bioanalytical Sciences"),
	MICROBIOLOGY("Microbiology"),
	BIOTECHNOLOGY("Biotechnology"),
	FTN("FTN"),

	// Abbreviated because these are the names in everyday use, and a course list is easier to
	// scan when the department column stays narrow.
	COMPUTER_SCIENCE("CS"),
	DATA_SCIENCE("DS"),
	INFORMATION_TECHNOLOGY("IT"),
	ENVIRONMENTAL_SCIENCE("EVS"),

	// Arts and commerce.
	ENGLISH("English"),
	HINDI("Hindi"),
	POLITICAL_SCIENCE("Pol. Sc."),
	ECONOMICS("Economics"),
	MANAGEMENT_STUDIES("Management Studies"),
	MASS_MEDIA("Mass Media"),
	HISTORY("History"),
	PHILOSOPHY("Philosophy"),
	PSYCHOLOGY("Psychology"),
	COMMERCE("Commerce");

	@NonNull
	private final String label;

	Department(@NonNull String label) {
		this.label = label;
	}

	@NonNull
	@Override
	public String toString() {
		return label;
	}

	/**
	 * Resolves a value from stored data or user input. Accepts the constant name, the display
	 * label, and loose spacing or punctuation, so a CSV written by hand still imports.
	 */
	@Nullable
	public static Department fromAny(@Nullable String raw) {
		return EnumLookup.find(Department.class, raw);
	}
}
