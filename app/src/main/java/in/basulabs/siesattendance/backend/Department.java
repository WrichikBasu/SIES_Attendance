package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;

public enum Department {

	PHYSICS,
	CHEMISTRY,
	MATHEMATICS,
	BOTANY,
	ZOOLOGY,
	BIOCHEMISTRY,
	STATISTICS,
	BIOANALYTICAL_SCIENCES,
	MICROBIOLOGY,
	FOOD_TECH_NUTRACEUTICALS,

	COMPUTER_SCIENCE,
	DATA_SCIENCE,
	INFORMATION_TECHNOLOGY,
	ENVIRONMENTAL_SCIENCE,

	ENGLISH,
	HINDI,
	POLITICAL_SCIENCE,
	ECONOMICS,
	MANAGEMENT_STUDIES,
	MASS_MEDIA,
	HISTORY,
	PHILOSOPHY,
	PSYCHOLOGY,
	COMMERCE;

	@NonNull
	public String toString() {

		switch (name()) {

			case "COMPUTER_SCIENCE":
				return "CS";
			case "DATA_SCIENCE":
				return "DS";
			case "INFORMATION_TECHNOLOGY":
				return "IT";
			case "ENVIRONMENTAL_SCIENCE":
				return "EVS";
			case "FOOD_TECH_NUTRACEUTICALS":
				return "FTN";
			case "POLITICAL_SCIENCE":
				return "Pol. Sc.";

			default:

				if (name().contains("_")) {

					int ind = name().indexOf('_');
					return name().charAt(0) + name().substring(1, ind).toLowerCase() + " "
							+ name().charAt(ind + 1) + name().substring(ind + 2).toLowerCase();


				} else {
					return name().charAt(0) + name().substring(1).toLowerCase();
				}
		}


	}
}
