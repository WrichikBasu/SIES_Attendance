package in.basulabs.siesattendance.backend.sync;

import static org.junit.Assert.assertEquals;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.WorkInfo;
import androidx.work.WorkInfo.State;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import in.basulabs.siesattendance.backend.sync.SyncScheduler.Move;

/**
 * What {@link SyncScheduler#requestSync} and {@link SyncScheduler#syncNow} do about the run
 * already asked for. {@code SyncSchedulingDeviceTest} checks the same against WorkManager on a
 * phone; this is the whole table.
 */
public class SyncSchedulerDecideTest {

	@Test
	public void nothingAskedForEnqueuesOne() {
		assertEquals(Move.ENQUEUE, SyncScheduler.decide(List.of(), false));
		assertEquals(Move.ENQUEUE, SyncScheduler.decide(List.of(), true));
	}

	@Test
	public void finishedRunsCountForNothing() {

		List<WorkInfo> done = List.of(run(State.SUCCEEDED, 0), run(State.FAILED, 3),
				run(State.CANCELLED, 0));

		assertEquals(Move.ENQUEUE, SyncScheduler.decide(done, false));
	}

	@Test
	public void aRunWaitingToStartIsLeftAlone() {
		assertEquals(Move.NOTHING, SyncScheduler.decide(List.of(run(State.ENQUEUED, 0)), false));
		assertEquals(Move.NOTHING, SyncScheduler.decide(List.of(run(State.ENQUEUED, 0)), true));
	}

	@Test
	public void aRunBackingOffIsLeftAloneUnlessSomebodyAsks() {

		List<WorkInfo> backingOff = List.of(run(State.ENQUEUED, 2));

		assertEquals("the outbox watch, or the worker", Move.NOTHING,
				SyncScheduler.decide(backingOff, false));
		assertEquals("the sync button", Move.ENQUEUE, SyncScheduler.decide(backingOff, true));
	}

	@Test
	public void aRunBackingOffWithOneQueuedBehindItIsStillReplacedWhenSomebodyAsks() {

		List<WorkInfo> chain = List.of(run(State.ENQUEUED, 1), run(State.BLOCKED, 0));

		assertEquals(Move.NOTHING, SyncScheduler.decide(chain, false));
		assertEquals(Move.ENQUEUE, SyncScheduler.decide(chain, true));
	}

	@Test
	public void aRunInProgressGetsOneQueuedBehindItAndNoMore() {

		assertEquals(Move.AFTER_THE_RUNNING_ONE,
				SyncScheduler.decide(List.of(run(State.RUNNING, 0)), false));
		assertEquals(Move.AFTER_THE_RUNNING_ONE,
				SyncScheduler.decide(List.of(run(State.RUNNING, 0)), true));

		List<WorkInfo> followed = List.of(run(State.RUNNING, 0), run(State.BLOCKED, 0));

		assertEquals(Move.NOTHING, SyncScheduler.decide(followed, false));
		assertEquals("never cancelled, even by hand", Move.NOTHING,
				SyncScheduler.decide(followed, true));
	}

	@NonNull
	private static WorkInfo run(@NonNull State state, int attempts) {
		return new WorkInfo(UUID.randomUUID(), state, Set.of(), Data.EMPTY, Data.EMPTY,
				attempts);
	}
}
