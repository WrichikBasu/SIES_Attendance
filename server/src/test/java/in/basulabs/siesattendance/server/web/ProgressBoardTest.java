package in.basulabs.siesattendance.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import in.basulabs.siesattendance.server.importer.Progress;

/** Who can see a running request's progress, and for how long. */
class ProgressBoardTest {

	private static final String ID = "0123456789abcdef";

	@Test
	void theAccountThatStartedItSeesItUntilItFinishes() {

		ProgressController.Board board = new ProgressController.Board();
		Progress progress = board.start(ID, 7);

		progress.stage("Importing", 40);
		progress.advance(15);
		progress.rename("Importing attendance");

		assertEquals(new Progress.State("Importing attendance", 15, 40), board.read(ID, 7),
				"A rename keeps the count");

		board.finish(ID);
		assertNull(board.read(ID, 7), "A finished request is gone");
	}

	@Test
	void anotherAccountSeesNothing() {

		ProgressController.Board board = new ProgressController.Board();
		board.start(ID, 7);

		assertNull(board.read(ID, 8), "Somebody else's import is not theirs to watch");
	}

	@Test
	void anUnusableIdIsNotWatched() {

		ProgressController.Board board = new ProgressController.Board();
		board.start("short", 7);
		board.start(null, 7);

		assertNull(board.read("short", 7), "An id under eight characters is not registered");
	}
}
