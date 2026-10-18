package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * How many login attempts one address gets.
 * <p>
 * {@code app_account} already locks an account after five consecutive failures, and that is the
 * defence that matters for one person's password. It is not a defence against somebody working
 * through a list of addresses — each account's counter is its own, so a thousand accounts tried
 * once each trips nothing — and it is itself a way to cause trouble: an attacker who knows an
 * address can spend five wrong guesses to lock its owner out. Both of those are about where the
 * attempts are coming from rather than which account they name, which is what this counts.
 *
 * <h3>Per instance, in memory</h3>
 * Deliberate, and worth knowing before a second instance is ever run: two instances mean two
 * counters and twice the allowance. For a college with a few hundred phones behind one service
 * that is the right trade — a shared counter means a round trip to something before the password
 * is even checked, and that something becomes a way to stop everybody signing in. If this is ever
 * run behind a load balancer with more than one instance, this is the thing to revisit, and the
 * answer is probably the proxy rather than a table.
 * <p>
 * Nothing is persisted, so a restart forgives everybody. That is acceptable: the account lock is
 * in the database and survives, and a restart is not something an attacker can ask for.
 */
public final class LoginRateLimiter {

	/**
	 * Ten in five minutes, unless the configuration says otherwise. Generous for a person — a
	 * mistyped password, a forgotten caps lock, a second account — and hopeless as a way of
	 * guessing anything.
	 */
	public static final int DEFAULT_ALLOWANCE = 10;

	public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);

	/**
	 * Addresses tracked at once, past which the oldest are forgotten.
	 * <p>
	 * A bound is necessary rather than tidy: without one, a flood from spoofed or rotating
	 * addresses would grow this map until the service ran out of memory, and a rate limiter that
	 * can be turned into a denial of service is worse than none.
	 */
	private static final int MAX_ADDRESSES = 10_000;

	private final Map<String, Deque<Long>> attempts = new HashMap<>();

	private final int allowance;

	@NonNull
	private final Duration window;

	public LoginRateLimiter() {
		this(DEFAULT_ALLOWANCE, DEFAULT_WINDOW);
	}

	/**
	 * Settable so that a test can prove the limit applies without waiting five minutes, and so
	 * that a college with one very busy address can raise it rather than turning it off. An
	 * allowance below one would refuse everybody, so it is clamped rather than trusted.
	 */
	public LoginRateLimiter(int allowance, @NonNull Duration window) {
		this.allowance = Math.max(1, allowance);
		this.window = window;
	}

	/**
	 * Counts an attempt and says whether it may proceed.
	 * <p>
	 * Counted whether or not the password turns out to be right, because a limiter that only
	 * counted failures would let an attacker with one valid account hammer the service as hard
	 * as it liked.
	 *
	 * @param address the caller's address, or null when there is none to key on — in which case
	 *                the attempt is allowed. Refusing a request for want of an address would
	 *                mean a misconfigured proxy locked out the whole college
	 */
	public synchronized boolean allow(@Nullable String address) {

		if (address == null) {
			return true;
		}

		long now = System.nanoTime();
		long cutoff = now - window.toNanos();

		forget(cutoff);

		if (attempts.size() >= MAX_ADDRESSES && !attempts.containsKey(address)) {
			// Full, and this address is not in it. Allowing is the safe direction: the account
			// lock is still there, and refusing would hand whoever filled the map the power to
			// stop everybody else signing in.
			return true;
		}

		Deque<Long> recent = attempts.computeIfAbsent(address, key -> new ArrayDeque<>());

		while (!recent.isEmpty() && recent.peekFirst() < cutoff) {
			recent.pollFirst();
		}

		if (recent.size() >= allowance) {
			return false;
		}

		recent.addLast(now);
		return true;
	}

	/** How long a refused caller should wait, for the {@code Retry-After} header. */
	@NonNull
	public Duration window() {
		return window;
	}

	/** Drops every address whose attempts have all aged out. */
	private void forget(long cutoff) {

		Iterator<Map.Entry<String, Deque<Long>>> entries = attempts.entrySet().iterator();

		while (entries.hasNext()) {

			Deque<Long> recent = entries.next().getValue();

			while (!recent.isEmpty() && recent.peekFirst() < cutoff) {
				recent.pollFirst();
			}

			if (recent.isEmpty()) {
				entries.remove();
			}
		}
	}
}
