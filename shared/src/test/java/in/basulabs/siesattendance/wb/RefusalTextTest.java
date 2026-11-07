package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.sync.RefusalText;
import in.basulabs.siesattendance.backend.sync.SyncJson;

/** What the sync screen says about rows the server would not take. */
public class RefusalTextTest {

	private static final String ROLL = "the roll is loaded by the office, so this roster stays "
			+ "on this device. Attendance still syncs.";

	private static SyncJson.Rejection refused(String table, long key, String reason,
	                                          String about) {
		return new SyncJson.Rejection(table, List.of(key), reason, true, about);
	}

	@Test
	public void aRefusalSaysWhatAndThenWhy() {

		assertEquals(List.of("Not accepted: R RAO's mark in CS-101 on Mon 14 Jul 2025, 09:00.\n"
						+ "The server refused this row: it is not yours to change."),
				RefusalText.summarise(List.of(refused("attendance", 1,
						"the server refused this row: it is not yours to change",
						"R RAO's mark in CS-101 on Mon 14 Jul 2025, 09:00"))));
	}

	@Test
	public void oneReasonForManyRowsIsOneEntryNamingTheFirstFew() {

		List<SyncJson.Rejection> rejections = new ArrayList<>();

		for (int at = 1; at <= 9; at++) {
			rejections.add(refused("students", at, ROLL, "student S" + at));
		}
		rejections.add(refused("courses", 50, "not yours", "course CS-101"));

		assertEquals(List.of(
						"Not accepted: student S1, student S2, student S3, student S4, "
								+ "student S5 and 4 more.\n"
								+ "The roll is loaded by the office, so this roster stays on "
								+ "this device. Attendance still syncs.",
						"Not accepted: course CS-101.\nNot yours."),
				RefusalText.summarise(rejections));
	}

	@Test
	public void sixAreAllNamedRatherThanFiveAndOneMore() {

		List<SyncJson.Rejection> rejections = new ArrayList<>();

		for (int at = 1; at <= 6; at++) {
			rejections.add(refused("students", at, ROLL, "S" + at));
		}

		assertEquals("Not accepted: S1, S2, S3, S4, S5 and S6.",
				RefusalText.summarise(rejections).get(0).split("\n")[0]);
	}

	@Test
	public void anOlderServerThatNamesNothingIsCountedByTable() {

		assertEquals(List.of("Not accepted: 3 marks and a lecture.\nNot yours."),
				RefusalText.summarise(List.of(
						refused("attendance", 1, "not yours", null),
						refused("attendance", 2, "not yours", null),
						refused("lectures", 3, "not yours", null),
						refused("attendance", 4, "not yours", null))));
	}
}
