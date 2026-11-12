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
 * How many login attempts an address gets, counted two ways.
 * <p>
 * {@code app_account} already locks an account after five consecutive failures, and that is the
 * defence that matters for one person's password. It is not a defence against somebody working
 * through a list of addresses — each account's counter is its own, so a thousand accounts tried
 * once each trips nothing — and it is itself a way to cause trouble: an attacker who knows an
 * address can spend five wrong guesses to lock its owner out. Both of those are about where the
 * attempts are coming from, which is what this counts.
 *
 * <h3>Two counts, because a college is one address</h3>
 * It used to count every attempt per address, successes included: ten in five minutes. Every
 * phone on the college's network reaches the server from the same public address, so a class of
 * sixty signing in at the start of a lab ran through the allowance in seconds and the rest were
 * told to wait five minutes (R4). Now:
 * <ul>
 *     <li><b>Per address and account, every attempt.</b> One person signing in, or one valid
 *     account being hammered for the cost of its password hash, is held to the allowance. Other
 *     people behind the same address have allowances of their own.</li>
 *     <li><b>Per address, failures only</b>, with a larger allowance. A list of accounts tried
 *     from one place fails, and fails fast; a lab of people who know their passwords does
 *     not.</li>
 * </ul>
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
	 * Attempts per address and account, unless the configuration says otherwise: ten in five
	 * minutes. Generous for a person — a mistyped password, a forgotten caps lock — and hopeless
	 * as a way of guessing anything.
	 */
	public static final int DEFAULT_ALLOWANCE = 10;

	/**
	 * Failures per address, as a multiple of the allowance. Thirty failed sign-ins in five
	 * minutes from one network is a lab having a bad morning; a list being worked through
	 * reaches it at once.
	 */
	public static final int FAILURES_PER_ALLOWANCE = 3;

	public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);

	/**
	 * Keys tracked at once, in each count, past which new ones are let through.
	 * <p>
	 * A bound is necessary rather than tidy: without one, a flood from spoofed or rotating
	 * addresses would grow these maps until the service ran out of memory, and a rate limiter
	 * that can be turned into a denial of service is worse than none.
	 */
	private static final int MAX_KEYS = 10_000;

	/** Every attempt, by address and account. */
	private final Map<String, Deque<Long>> attempts = new HashMap<>();

	/** Failed attempts, by address. */
	private final Map<String, Deque<Long>> failures = new HashMap<>();

	private final int allowance;

	private final int failureAllowance;

	@NonNull
	private final Duration window;

	public LoginRateLimiter() {
		this(DEFAULT_ALLOWANCE, DEFAULT_WINDOW);
	}

	/**
	 * Settable so that a test can prove the limit applies without waiting five minutes, and so
	 * that a college with an unusually busy network can raise it rather than turning it off. An
	 * allowance below one would refuse everybody, so it is clamped rather than trusted.
	 */
	public LoginRateLimiter(int allowance, @NonNull Duration window) {
		this.allowance = Math.max(1, allowance);
		this.failureAllowance = this.allowance * FAILURES_PER_ALLOWANCE;
		this.window = window;
	}

	/**
	 * An attempt naming no account, counted as one account's: what a caller with no address
	 * book of its own is held to.
	 */
	public boolean allow(@Nullable String address) {
		return allow(address, null);
	}

	/**
	 * Counts an attempt and says whether it may proceed.
	 * <p>
	 * Refused when this address and account have used their allowance, whether or not those
	 * attempts succeeded, or when this address has failed too often across every account.
	 *
	 * @param address the caller's address, or null when there is none to key on — in which case
	 *                the attempt is allowed. Refusing a request for want of an address would
	 *                mean a misconfigured proxy locked out the whole college
	 * @param account the address the attempt names, compared without case
	 */
	public synchronized boolean allow(@Nullable String address, @Nullable String account) {

		if (address == null) {
			return true;
		}

		long now = System.nanoTime();
		long cutoff = now - window.toNanos();

		forget(attempts, cutoff);
		forget(failures, cutoff);

		Deque<Long> failed = failures.get(address);

		if (failed != null && failed.size() >= failureAllowance) {
			return false;
		}

		String key = address + "\u0000"
				+ (account == null ? "" : account.trim().toLowerCase(java.util.Locale.ROOT));

		if (attempts.size() >= MAX_KEYS && !attempts.containsKey(key)) {
			// Full, and this key is not in it. Allowing is the safe direction: the account lock
			// is still there, and refusing would hand whoever filled the map the power to stop
			// everybody else signing in.
			return true;
		}

		Deque<Long> recent = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());

		if (recent.size() >= allowance) {
			return false;
		}

		recent.addLast(now);
		return true;
	}

	/** Records that an attempt from this address failed. */
	public synchronized void failed(@Nullable String address) {

		if (address == null) {
			return;
		}

		if (failures.size() >= MAX_KEYS && !failures.containsKey(address)) {
			return;
		}

		failures.computeIfAbsent(address, ignored -> new ArrayDeque<>())
				.addLast(System.nanoTime());
	}

	/** How long a refused caller should wait, for the {@code Retry-After} header. */
	@NonNull
	public Duration window() {
		return window;
	}

	/** Drops every key whose entries have all aged out. */
	private static void forget(@NonNull Map<String, Deque<Long>> counts, long cutoff) {

		Iterator<Map.Entry<String, Deque<Long>>> entries = counts.entrySet().iterator();

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
