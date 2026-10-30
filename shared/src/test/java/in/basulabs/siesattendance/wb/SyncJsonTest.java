package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import in.basulabs.siesattendance.backend.sync.OutboxOp;
import in.basulabs.siesattendance.backend.sync.SyncIntent;
import in.basulabs.siesattendance.backend.sync.SyncJson;

/**
 * The sync wire format, both directions.
 * <p>
 * Written and read by hand because the phone has no Jackson and the JVM has no {@code org.json},
 * and the price of that is these tests. The cases that matter are the ones a phone will meet and
 * a developer will not think of: a lecture's notes with a quote in them, a key too large for a
 * double, and a row of nulls.
 */
public class SyncJsonTest {

	@Test
	public void aPushRoundTripsThroughItsOwnReader() {

		Map<String, Object> row = new LinkedHashMap<>();
		row.put("lectureID", 36028797018963968L);
		row.put("courseID", 7L);
		row.put("batchID", null);
		row.put("startDateTime", "1752456600|0|Asia/Kolkata");
		row.put("notes", "Rao said \"bring a calculator\"\nand a pen");

		SyncIntent intent = new SyncIntent("lectures", List.of(36028797018963968L),
				OutboxOp.UPSERT, row);

		String body = SyncJson.writePush(List.of(intent));

		// Read back as a pull would be, which is the same shape: the changes array and the
		// intents array carry identical objects, deliberately.
		SyncJson.PullPage page = SyncJson.parsePull(
				"{\"watermark\":\"x@1\",\"more\":false,\"changes\":"
						+ body.substring(body.indexOf('[')));

		assertEquals(1, page.changes().size());

		SyncIntent back = page.changes().get(0);

		assertEquals("lectures", back.table());
		assertEquals(List.of(36028797018963968L), back.key());
		assertEquals(OutboxOp.UPSERT, back.op());
		assertEquals(row.get("notes"), back.row().get("notes"));
		assertEquals(7L, back.row().get("courseID"));
		assertNull(back.row().get("batchID"));
	}

	@Test
	public void aKeyKeepsEveryBitOfItself() {

		// A structured key is a 55-bit number. Parsed as a double it would come back rounded,
		// and the row would be written under a key one away from the one the phone issued --
		// which is a different lecture.
		long key = (1L << 55) + 12345;

		SyncJson.PullPage page = SyncJson.parsePull(
				"{\"watermark\":\"x@1\",\"more\":false,\"changes\":[{\"table\":\"lectures\","
						+ "\"key\":[" + key + "],\"op\":\"DELETE\",\"row\":null}]}");

		assertEquals(key, (long) page.changes().get(0).key().get(0));
	}

	@Test
	public void everyCharacterAStringCanHoldSurvives() {

		Map<String, Object> row = new LinkedHashMap<>();
		row.put("notes", "quote \" backslash \\ tab \t newline \n bell \u0007 slash /");
		row.put("studentID", 1L);

		String body = SyncJson.writePush(List.of(
				new SyncIntent("attendance", List.of(1L, 2L), OutboxOp.UPSERT, row)));

		SyncJson.PullPage page = SyncJson.parsePull(
				"{\"watermark\":\"x@1\",\"more\":false,\"changes\":"
						+ body.substring(body.indexOf('[')));

		assertEquals(row.get("notes"), page.changes().get(0).row().get("notes"));
	}

	@Test
	public void aDeleteCarriesNoRow() {

		String body = SyncJson.writePush(List.of(
				new SyncIntent("attendance", List.of(1L, 2L), OutboxOp.DELETE, null)));

		assertTrue(body, body.contains("\"row\":null"));
		assertTrue(body, body.contains("\"op\":\"DELETE\""));
	}

	@Test
	public void anUpsertWithNoRowIsRefusedWhereItIsBuilt() {

		// Not at the server and not on the wire: the record refuses it, so the mistake cannot
		// leave the device.
		assertThrows(IllegalArgumentException.class, () ->
				new SyncIntent("lectures", List.of(1L), OutboxOp.UPSERT, null));

		assertThrows(IllegalArgumentException.class, () ->
				new SyncIntent("lectures", List.of(), OutboxOp.DELETE, null));
	}

	@Test
	public void aPullPageSaysWhereToResumeAndWhetherThereIsMore() {

		SyncJson.PullPage page = SyncJson.parsePull(
				"{\"watermark\":\"2026-10-19T08:15:30.123456Z@412\",\"more\":true,"
						+ "\"changes\":[]}");

		assertEquals("2026-10-19T08:15:30.123456Z@412", page.watermark());
		assertTrue(page.more());
		assertTrue(page.changes().isEmpty());
	}

	@Test
	public void aPushOutcomeCarriesItsRefusals() {

		SyncJson.PushOutcome outcome = SyncJson.parsePushOutcome(
				"{\"accepted\":2,\"rejected\":[{\"table\":\"lectures\",\"key\":[9],"
						+ "\"reason\":\"not yours to change\"}]}");

		assertEquals(2, outcome.accepted());
		assertEquals(1, outcome.rejected().size());
		assertEquals("lectures", outcome.rejected().get(0).table());
		assertEquals("not yours to change", outcome.rejected().get(0).reason());
	}

	@Test
	public void anEmptyOutcomeIsNotAFailure() {

		SyncJson.PushOutcome outcome = SyncJson.parsePushOutcome(
				"{\"accepted\":0,\"rejected\":[]}");

		assertEquals(0, outcome.accepted());
		assertTrue(outcome.rejected().isEmpty());
	}

	@Test
	public void whitespaceBetweenEverythingIsFine() {

		SyncJson.PullPage page = SyncJson.parsePull(
				"{\n  \"watermark\" : \"x@1\" ,\n  \"more\" : false ,\n"
						+ "  \"changes\" : [ { \"table\" : \"lectures\" , \"key\" : [ 1 ] ,"
						+ " \"op\" : \"DELETE\" , \"row\" : null } ]\n}");

		assertEquals(1, page.changes().size());
		assertFalse(page.more());
	}

	@Test
	public void nonsenseIsRefusedRatherThanHalfApplied() {

		// Every one of these is a sync that fails and keeps its watermark, which is the right
		// outcome: applying part of an answer is how a device ends up with rows nobody sent.
		for (String bad : Arrays.asList(
				"",
				"not json",
				"{\"watermark\":\"x@1\"",
				"{\"watermark\":1,\"changes\":[]}",
				"{\"watermark\":\"x@1\",\"changes\":{}}",
				"{\"watermark\":\"x@1\",\"changes\":[{\"table\":\"lectures\"}]}",
				"{\"watermark\":\"x@1\",\"changes\":[{\"table\":\"lectures\",\"key\":[],"
						+ "\"op\":\"DELETE\"}]}",
				"{\"watermark\":\"x@1\",\"changes\":[{\"table\":\"lectures\",\"key\":[1],"
						+ "\"op\":\"MERGE\",\"row\":null}]}",
				"{\"watermark\":\"x@1\",\"changes\":[{\"table\":\"lectures\","
						+ "\"key\":[\"one\"],\"op\":\"DELETE\",\"row\":null}]}")) {

			assertThrows("should have been refused: " + bad,
					IllegalArgumentException.class, () -> SyncJson.parsePull(bad));
		}
	}

	@Test
	public void anOperationALaterServerInventsIsRefusedRatherThanSkipped() {

		// Skipping it would move the watermark past a change this build never applied, and the
		// device would be quietly missing a row for ever.
		assertThrows(IllegalArgumentException.class, () -> SyncJson.parsePull(
				"{\"watermark\":\"x@1\",\"more\":false,\"changes\":[{\"table\":\"lectures\","
						+ "\"key\":[1],\"op\":\"ARCHIVE\",\"row\":null}]}"));
	}

	@Test
	public void anEmptyPushIsStillWellFormed() {
		assertEquals("{\"intents\":[]}", SyncJson.writePush(List.of()));
	}

	@Test
	public void aRefusalIsReadForItsSentenceAndNotItsCode() {

		// What reaches a screen. The code is for this program; the message is written for the
		// person who caused the refusal, and showing them the body instead -- which the phone
		// used to do -- puts JSON in front of a professor.
		assertEquals("A password must be at least 12 characters long.",
				SyncJson.messageIn("{\"code\":\"weak_password\",\"message\":\"A password must "
						+ "be at least 12 characters long.\"}"));
	}

	@Test
	public void aBodyThatIsNotOneOfOursIsNotASecondFailure() {

		// Reached while already handling a failure: a proxy's HTML error page, an empty body
		// from a connection that was cut, a 502 from something that is not this server. All of
		// them have to come back as "nothing to quote" rather than throwing.
		for (String notOurs : List.of("", "<html><body>502 Bad Gateway</body></html>",
				"{", "{\"code\":\"weak_password\"}", "{\"message\":\"\"}",
				"{\"message\":12}", "[]")) {

			assertNull("should have carried no sentence: " + notOurs,
					SyncJson.messageIn(notOurs));
		}
	}

	@Test
	public void aCountMissingFromAnAnswerIsNothingToReport() {

		assertEquals(3, SyncJson.numberIn("{\"otherSessionsRevoked\":3}",
				"otherSessionsRevoked"));

		// A password change that ended no other session says so with a zero, and one from a
		// server that does not mention it at all is the same thing to the person reading it.
		assertEquals(0, SyncJson.numberIn("{\"otherSessionsRevoked\":0}",
				"otherSessionsRevoked"));
		assertEquals(0, SyncJson.numberIn("{}", "otherSessionsRevoked"));
		assertEquals(0, SyncJson.numberIn("not json", "otherSessionsRevoked"));
	}

	@Test
	public void theCoursesACrRepresentsAreReadOutOfMe() {

		String me = "{\"kind\":\"STUDENT\",\"mayKeepARoll\":false,\"represents\":["
				+ "{\"studentId\":\"2001\",\"courseId\":\"370666966312026112\","
				+ "\"courseCode\":\"CS-101\",\"live\":true},"
				+ "{\"studentId\":\"2001\",\"courseId\":3002,\"courseCode\":\"CS-102\"}]}";

		assertEquals(List.of(370666966312026112L, 3002L), SyncJson.courseIdsIn(me, "represents"));

		assertEquals("none is an answer: every appointment has ended",
				List.of(), SyncJson.courseIdsIn("{\"represents\":[]}", "represents"));

		assertNull("and saying nothing is a different one",
				SyncJson.courseIdsIn("{\"mayKeepARoll\":true}", "represents"));
	}

	@Test
	public void theRolesAreReadOutOfMe() {

		String me = "{\"roles\":[{\"userRoleId\":1,\"role\":\"ADMIN\",\"department\":null},"
				+ "{\"userRoleId\":7,\"role\":\"HOD\",\"department\":\"COMPUTER_SCIENCE\"}]}";

		assertEquals(List.of("ADMIN", "HOD"), SyncJson.textsIn(me, "roles", "role"));
		assertEquals(List.of(), SyncJson.textsIn("{\"roles\":[]}", "roles", "role"));
		assertNull(SyncJson.textsIn("{}", "roles", "role"));
	}
}
