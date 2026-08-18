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

	PHYSICS("Physics"),
	CHEMISTRY("Chemistry"),
	MATHEMATICS("Mathematics"),
	BOTANY("Botany"),
	ZOOLOGY("Zoology"),
	BIOCHEMISTRY("Biochemistry"),
	STATISTICS("Statistics"),
	BIOANALYTICAL_SCIENCES("Bioanalytical Sciences"),
	MICROBIOLOGY("Microbiology"),
	FTN("FTN"),

	COMPUTER_SCIENCE("CS"),
	DATA_SCIENCE("DS"),
	INFORMATION_TECHNOLOGY("IT"),
	ENVIRONMENTAL_SCIENCE("EVS"),

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
