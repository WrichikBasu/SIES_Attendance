package in.basulabs.siesattendance.server.security;

import androidx.annotation.NonNull;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * How a password becomes a stored hash, and how a stored hash is checked.
 * <p>
 * Argon2id, which is the memory-hard choice and the one to use when there is no reason to pick
 * something else. Not bcrypt: bcrypt caps the password at 72 bytes and silently ignores the
 * rest, and its cost parameter buys only time, not memory, so it is the cheaper thing for an
 * attacker with a graphics card to attack.
 *
 * <h3>Why the parameters travel with the hash</h3>
 * Spring Security's encoding is {@code {argon2id}$argon2id$v=19$m=...,t=...,p=...$salt$hash} —
 * the memory, iterations and parallelism a hash was made with are written into the hash itself.
 * That is what makes them raisable: {@link #encoder()} can be made more expensive tomorrow and
 * every existing hash still verifies, because {@code matches} reads the old parameters out of
 * the stored string rather than assuming today's. The alternative, parameters held only in
 * configuration, means a change invalidates every password at once.
 * <p>
 * {@link #needsRehash} is the other half of that: after a successful login, a hash made with
 * weaker parameters than the current ones can be replaced, so the estate catches up one login at
 * a time instead of in a flag day.
 *
 * <h3>The numbers</h3>
 * 19 MiB of memory, two passes, one lane — the second of OWASP's recommended Argon2id settings,
 * and the one Spring Security's own {@code defaultsForSpringSecurity_v5_8} uses. Left as the
 * library's default on purpose: a hand-picked set of three numbers is how somebody ends up with
 * a hash that is cheaper than the default while looking deliberate.
 */
public final class Passwords {

	/**
	 * The current parameters, repeated here only so {@link #needsRehash} can compare against
	 * them. Change these and the encoder together, never one alone.
	 */
	private static final int MEMORY_KIB = 16384;

	private static final int ITERATIONS = 2;

	private static final int PARALLELISM = 1;

	private static final PasswordEncoder ENCODER =
			Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

	private Passwords() {
	}

	/** The one encoder. Stateless and thread-safe, which is why it is a constant. */
	@NonNull
	public static PasswordEncoder encoder() {
		return ENCODER;
	}

	/**
	 * Whether a stored hash was made with weaker parameters than the ones in use now, and so
	 * should be replaced the next time its owner successfully signs in.
	 * <p>
	 * Reads the parameters out of the stored string rather than guessing, because that is the
	 * whole reason they are written there. A string this cannot parse is treated as needing a
	 * rehash: either it predates this format or it is not one of ours, and both are answered by
	 * writing a current hash the next time the password is in hand.
	 */
	public static boolean needsRehash(@NonNull String storedHash) {

		int memory = parameter(storedHash, "m=");
		int iterations = parameter(storedHash, "t=");
		int parallelism = parameter(storedHash, "p=");

		if (memory < 0 || iterations < 0 || parallelism < 0) {
			return true;
		}
		return memory < MEMORY_KIB || iterations < ITERATIONS || parallelism < PARALLELISM;
	}

	/** One {@code key=value} out of the encoded parameters, or -1 when it is not there. */
	private static int parameter(@NonNull String hash, @NonNull String key) {

		int at = hash.indexOf(key);

		if (at < 0) {
			return -1;
		}

		int from = at + key.length();
		int to = from;

		while (to < hash.length() && Character.isDigit(hash.charAt(to))) {
			to++;
		}

		if (to == from) {
			return -1;
		}

		try {
			return Integer.parseInt(hash.substring(from, to));
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
