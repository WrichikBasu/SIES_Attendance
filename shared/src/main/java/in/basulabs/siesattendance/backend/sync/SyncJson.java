package in.basulabs.siesattendance.backend.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sync wire format, written and read by hand.
 * <p>
 * Hand-rolled for one reason: this has to run on the phone, where there is no Jackson, and in the
 * JVM, where there is no {@code org.json}. A parser is the price of having one definition of the
 * format instead of two — and having one definition is what lets a server test parse the server's
 * own response with the device's own reader, which is the only way to find out that the two agree
 * without a handset in the room.
 * <p>
 * Deliberately not a general JSON library. It reads objects, arrays, strings, numbers, booleans
 * and null, which is the whole of what this format uses, and it refuses anything else rather than
 * guessing. A number is a {@code Long} when it has no fraction and a {@code Double} when it does,
 * because every number this format carries is a key, a count or a timestamp.
 *
 * <h3>The format</h3>
 * A push:
 * <pre>
 * {"intents":[{"table":"lectures","key":[123],"op":"UPSERT","row":{"courseID":7,…}}]}
 * </pre>
 * A pull's answer:
 * <pre>
 * {"watermark":"2026-10-19T…Z@412","more":false,
 *  "changes":[{"table":"attendance","key":[123,45],"op":"DELETE","row":null}]}
 * </pre>
 * Row values are exactly as the device holds them: 0 or 1 for a boolean, days since 1970 for a
 * date, {@code "epochSecond|nano|zone"} for a timestamp, the constant's name for an enum. The
 * phone therefore converts nothing, and the server converts in both directions with the code the
 * backup importer already uses.
 */
public final class SyncJson {

	/** A pull's answer, parsed. */
	public record PullPage(@NonNull String watermark, boolean more,
	                       @NonNull List<SyncIntent> changes) {
	}

	/** A push's answer, parsed. */
	public record PushOutcome(int accepted, @NonNull List<Rejection> rejected) {
	}

	/**
	 * One row the server would not take, and what it said about it.
	 *
	 * @param permanent whether the server says sending it again cannot help. Absent from the
	 *                  field means false, which is the cautious reading: an older server that
	 *                  does not send it gets the behaviour it was written for, where every
	 *                  refusal is kept and retried
	 */
	public record Rejection(@NonNull String table, @NonNull List<Long> key,
	                        @NonNull String reason, boolean permanent) {
	}

	private SyncJson() {
	}

	// ── Writing ──────────────────────────────────────────────────────────

	/** The body of a push. */
	@NonNull
	public static String writePush(@NonNull List<SyncIntent> intents) {

		StringBuilder out = new StringBuilder("{\"intents\":[");

		for (int at = 0; at < intents.size(); at++) {
			out.append(at == 0 ? "" : ",");
			writeIntent(out, intents.get(at));
		}

		return out.append("]}").toString();
	}

	private static void writeIntent(@NonNull StringBuilder out, @NonNull SyncIntent intent) {

		out.append("{\"table\":");
		writeString(out, intent.table());
		out.append(",\"key\":[");

		for (int at = 0; at < intent.key().size(); at++) {
			out.append(at == 0 ? "" : ",").append(intent.key().get(at));
		}

		out.append("],\"op\":");
		writeString(out, intent.op().name());
		out.append(",\"row\":");

		if (intent.row() == null) {
			out.append("null");
		} else {
			writeObject(out, intent.row());
		}

		out.append("}");
	}

	private static void writeObject(@NonNull StringBuilder out,
	                                @NonNull Map<String, Object> values) {

		out.append("{");
		boolean first = true;

		for (Map.Entry<String, Object> entry : values.entrySet()) {

			out.append(first ? "" : ",");
			first = false;

			writeString(out, entry.getKey());
			out.append(":");
			writeValue(out, entry.getValue());
		}

		out.append("}");
	}

	private static void writeValue(@NonNull StringBuilder out, @Nullable Object value) {

		if (value == null) {
			out.append("null");
		} else if (value instanceof Boolean flag) {
			out.append(flag);
		} else if (value instanceof Number number) {
			out.append(number);
		} else {
			writeString(out, String.valueOf(value));
		}
	}

	/**
	 * A JSON string, escaped.
	 * <p>
	 * The one part of this class that is not about the format but about correctness: a lecture's
	 * notes are typed by a person and will eventually contain a quote, a backslash or a newline.
	 * Control characters go out as {@code \ u00xx} because a raw one is not legal JSON and the
	 * server's parser would refuse the whole batch over one of them.
	 */
	private static void writeString(@NonNull StringBuilder out, @NonNull String value) {

		out.append('"');

		for (int at = 0; at < value.length(); at++) {

			char c = value.charAt(at);

			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}

		out.append('"');
	}

	// ── Reading ──────────────────────────────────────────────────────────

	/**
	 * A pull's answer.
	 *
	 * @throws IllegalArgumentException if the body is not the shape this format describes. The
	 *                                  caller treats that as a failed sync and keeps its
	 *                                  watermark, because applying half an answer is worse than
	 *                                  applying none of it
	 */
	@NonNull
	public static PullPage parsePull(@NonNull String body) {

		Map<String, Object> root = asObject(new Reader(body).readValue(), "the response");

		List<SyncIntent> changes = new ArrayList<>();

		for (Object entry : asArray(root.get("changes"), "changes")) {
			changes.add(intentFrom(asObject(entry, "a change")));
		}

		return new PullPage(text(root.get("watermark"), "watermark"),
				Boolean.TRUE.equals(root.get("more")), changes);
	}

	/** A push's answer. */
	@NonNull
	public static PushOutcome parsePushOutcome(@NonNull String body) {

		Map<String, Object> root = asObject(new Reader(body).readValue(), "the response");
		List<Rejection> rejected = new ArrayList<>();

		for (Object entry : asArray(root.get("rejected"), "rejected")) {

			Map<String, Object> rejection = asObject(entry, "a rejection");

			rejected.add(new Rejection(text(rejection.get("table"), "table"),
					keyFrom(rejection.get("key")),
					text(rejection.get("reason"), "reason"),
					Boolean.TRUE.equals(rejection.get("permanent"))));
		}

		Object accepted = root.get("accepted");

		if (!(accepted instanceof Number count)) {
			throw new IllegalArgumentException("accepted is not a number");
		}

		return new PushOutcome(count.intValue(), rejected);
	}

	/** The machine-readable {@code code} of an error body, or null. */
	@Nullable
	public static String codeIn(@NonNull String body) {

		try {
			Object code = asObject(new Reader(body).readValue(), "the response").get("code");
			return code instanceof String text && !text.isBlank() ? text : null;

		} catch (RuntimeException notJson) {
			return null;
		}
	}

	/**
	 * The sentence out of a refusal, or null when the body does not carry one.
	 * <p>
	 * Every error this server returns is {@code {"code": …, "message": …}}, and the message is
	 * written to be read by the person who caused it — "A password must be at least 12
	 * characters long", not "weak_password". Showing the raw body instead, which is what the
	 * phone used to do, puts JSON on a professor's screen.
	 * <p>
	 * Null rather than throwing: this is reached while already handling a failure, and a body
	 * that is not JSON at all — a proxy's HTML error page, say — must not turn into a second
	 * one.
	 */
	@Nullable
	public static String messageIn(@NonNull String body) {

		try {
			Object message = asObject(new Reader(body).readValue(), "the response")
					.get("message");

			return message instanceof String sentence && !sentence.isBlank() ? sentence : null;

		} catch (RuntimeException notJson) {
			return null;
		}
	}

	/**
	 * A true/false field out of a flat answer, or {@code fallback} when it does not carry one.
	 * <p>
	 * {@code fallback} rather than false, because the two answers mean different things to the
	 * caller: a server that said nothing is not a server that said no. The phone asks
	 * {@code /me} whether to offer the roster screens, and an older server with no opinion
	 * should leave the app as it was rather than quietly taking screens away.
	 */
	public static boolean flagIn(@NonNull String body, @NonNull String field,
	                             boolean fallback) {

		try {
			Object value = asObject(new Reader(body).readValue(), "the response").get(field);
			return value instanceof Boolean flag ? flag : fallback;

		} catch (RuntimeException notJson) {
			return fallback;
		}
	}

	/**
	 * A flag inside an object field, such as {@code imports.anyDepartment} in {@code /me}, or
	 * {@code fallback} when the answer does not carry it.
	 */
	public static boolean flagAt(@NonNull String body, @NonNull String object,
	                             @NonNull String field, boolean fallback) {

		try {
			Object outer = asObject(new Reader(body).readValue(), "the response").get(object);

			if (!(outer instanceof Map<?, ?>)) {
				return fallback;
			}
			return asObject(outer, object).get(field) instanceof Boolean flag ? flag : fallback;

		} catch (RuntimeException notJson) {
			return fallback;
		}
	}

	/**
	 * A list of text inside an object field, such as {@code imports.departments}, or null when
	 * the answer does not carry it.
	 */
	@Nullable
	public static List<String> textListAt(@NonNull String body, @NonNull String object,
	                                      @NonNull String field) {

		try {
			Object outer = asObject(new Reader(body).readValue(), "the response").get(object);

			if (!(outer instanceof Map<?, ?>)) {
				return null;
			}

			if (!(asObject(outer, object).get(field) instanceof List<?> items)) {
				return null;
			}

			List<String> texts = new ArrayList<>();
			for (Object item : items) {
				if (item instanceof String text) {
					texts.add(text);
				}
			}
			return texts;

		} catch (RuntimeException notJson) {
			return null;
		}
	}

	/**
	 * One text field of every object in a list field, or null when the answer has no such list.
	 * <p>
	 * For {@code /me}'s {@code roles}: the {@code role} of each grant in force. Null and empty
	 * mean different things, as for {@link #courseIdsIn}.
	 */
	@Nullable
	public static List<String> textsIn(@NonNull String body, @NonNull String field,
	                                   @NonNull String itemField) {

		try {
			Object value = asObject(new Reader(body).readValue(), "the response").get(field);

			if (!(value instanceof List<?> items)) {
				return null;
			}

			List<String> texts = new ArrayList<>();

			for (Object item : items) {
				if (asObject(item, field).get(itemField) instanceof String text) {
					texts.add(text);
				}
			}
			return texts;

		} catch (RuntimeException notJson) {
			return null;
		}
	}

	/**
	 * The {@code courseId} of every object in a list field, or null when the answer has no
	 * such list.
	 * <p>
	 * For {@code /me}'s {@code represents}: the courses a class representative may mark. Null
	 * and empty are different answers and the caller needs both. Empty is the server saying
	 * this account represents nothing; null is a server that said nothing at all, an older one,
	 * and whatever the phone already knew should stand. A key travels as digits in a string,
	 * like every key on this wire, and a number is read too.
	 */
	@Nullable
	public static List<Long> courseIdsIn(@NonNull String body, @NonNull String field) {

		try {
			Object value = asObject(new Reader(body).readValue(), "the response").get(field);

			if (!(value instanceof List<?> items)) {
				return null;
			}

			List<Long> ids = new ArrayList<>();

			for (Object item : items) {

				Object id = asObject(item, field).get("courseId");

				if (id instanceof Number number) {
					ids.add(number.longValue());
				} else if (id instanceof String digits) {
					ids.add(Long.parseLong(digits.trim()));
				}
			}
			return ids;

		} catch (RuntimeException notJson) {
			return null;
		}
	}

	/**
	 * A whole number out of a flat answer, or zero when it does not carry one.
	 * <p>
	 * Zero rather than an exception or a null, because the two answers read this way — how many
	 * other sessions a password change revoked, and the like — are counts being reported to a
	 * person. A server that said nothing about it is a server with nothing to report, and
	 * failing the call over the absence of a number nobody acts on would be worse than saying
	 * "and nothing else happened".
	 */
	public static int numberIn(@NonNull String body, @NonNull String field) {

		try {
			Object value = asObject(new Reader(body).readValue(), "the response").get(field);
			return value instanceof Number number ? number.intValue() : 0;

		} catch (RuntimeException notJson) {
			return 0;
		}
	}

	@NonNull
	private static SyncIntent intentFrom(@NonNull Map<String, Object> change) {

		OutboxOp op = OutboxOp.fromStored(text(change.get("op"), "op"));

		if (op == null) {
			// A later server speaking about something this build does not know. Refusing the
			// page is right: applying the rest and skipping this one would move the watermark
			// past a change the device never made.
			throw new IllegalArgumentException("unknown operation: " + change.get("op"));
		}

		Object row = change.get("row");

		return new SyncIntent(text(change.get("table"), "table"), keyFrom(change.get("key")), op,
				row == null ? null : asObject(row, "row"));
	}

	@NonNull
	private static List<Long> keyFrom(@Nullable Object value) {

		List<Long> key = new ArrayList<>();

		for (Object part : asArray(value, "key")) {

			if (!(part instanceof Number number)) {
				throw new IllegalArgumentException("a key column is not a number: " + part);
			}
			key.add(number.longValue());
		}

		if (key.isEmpty()) {
			throw new IllegalArgumentException("a change with no key");
		}
		return key;
	}

	@NonNull
	private static String text(@Nullable Object value, @NonNull String what) {

		if (!(value instanceof String string)) {
			throw new IllegalArgumentException(what + " is not a string: " + value);
		}
		return string;
	}

	@SuppressWarnings("unchecked")
	@NonNull
	private static Map<String, Object> asObject(@Nullable Object value, @NonNull String what) {

		if (!(value instanceof Map<?, ?> map)) {
			throw new IllegalArgumentException(what + " is not an object");
		}
		return (Map<String, Object>) map;
	}

	@NonNull
	private static List<Object> asArray(@Nullable Object value, @NonNull String what) {

		if (value == null) {
			return List.of();
		}

		if (!(value instanceof List<?> list)) {
			throw new IllegalArgumentException(what + " is not an array");
		}

		return new ArrayList<>(list);
	}

	/**
	 * A recursive-descent reader over the subset of JSON this format uses.
	 * <p>
	 * Not a general parser and not trying to be: no exponents in integers that should be keys,
	 * no duplicate-key resolution, no comments. Anything it does not recognise is an
	 * {@link IllegalArgumentException}, which the caller turns into a failed sync.
	 */
	private static final class Reader {

		@NonNull
		private final String source;

		private int at;

		Reader(@NonNull String source) {
			this.source = source;
		}

		@Nullable
		Object readValue() {

			skipSpace();

			if (at >= source.length()) {
				throw new IllegalArgumentException("the body is empty");
			}

			char c = source.charAt(at);

			return switch (c) {
				case '{' -> readObject();
				case '[' -> readArray();
				case '"' -> readString();
				case 't' -> readLiteral("true", Boolean.TRUE);
				case 'f' -> readLiteral("false", Boolean.FALSE);
				case 'n' -> readLiteral("null", null);
				default -> readNumber();
			};
		}

		@NonNull
		private Map<String, Object> readObject() {

			Map<String, Object> values = new LinkedHashMap<>();
			expect('{');
			skipSpace();

			if (peek() == '}') {
				at++;
				return values;
			}

			while (true) {

				skipSpace();
				String name = readString();
				skipSpace();
				expect(':');
				values.put(name, readValue());
				skipSpace();

				char c = next("an object with no end");

				if (c == '}') {
					return values;
				}

				if (c != ',') {
					throw new IllegalArgumentException("expected , or } at " + (at - 1));
				}
			}
		}

		@NonNull
		private List<Object> readArray() {

			List<Object> values = new ArrayList<>();
			expect('[');
			skipSpace();

			if (peek() == ']') {
				at++;
				return values;
			}

			while (true) {

				values.add(readValue());
				skipSpace();

				char c = next("an array with no end");

				if (c == ']') {
					return values;
				}

				if (c != ',') {
					throw new IllegalArgumentException("expected , or ] at " + (at - 1));
				}
			}
		}

		@NonNull
		private String readString() {

			expect('"');
			StringBuilder out = new StringBuilder();

			while (true) {

				if (at >= source.length()) {
					throw new IllegalArgumentException("a string with no end");
				}

				char c = source.charAt(at++);

				if (c == '"') {
					return out.toString();
				}

				if (c != '\\') {
					out.append(c);
					continue;
				}

				char escape = next("an escape with no character after it");

				switch (escape) {
					case '"' -> out.append('"');
					case '\\' -> out.append('\\');
					case '/' -> out.append('/');
					case 'n' -> out.append('\n');
					case 'r' -> out.append('\r');
					case 't' -> out.append('\t');
					case 'b' -> out.append('\b');
					case 'f' -> out.append('\f');
					case 'u' -> {

						if (at + 4 > source.length()) {
							throw new IllegalArgumentException("a \\u escape with no digits");
						}

						try {
							out.append((char) Integer.parseInt(
									source.substring(at, at + 4), 16));
						} catch (NumberFormatException e) {
							throw new IllegalArgumentException("a \\u escape that is not hex");
						}
						at += 4;
					}
					default -> throw new IllegalArgumentException("bad escape \\" + escape);
				}
			}
		}

		@Nullable
		private Object readNumber() {

			int from = at;

			while (at < source.length() && "+-.eE0123456789".indexOf(source.charAt(at)) >= 0) {
				at++;
			}

			String raw = source.substring(from, at);

			if (raw.isEmpty()) {
				throw new IllegalArgumentException("not a value at " + from);
			}

			/*
			 * A Long unless it genuinely is not one: every number here is a key, a count or a
			 * timestamp, and a key read as a double comes back rounded -- which is a different
			 * lecture, written under a key no phone issued.
			 *
			 * An if, not a ternary. `cond ? Double.valueOf(x) : Long.valueOf(y)` promotes both
			 * branches to double and boxes the result as a Double, so every integer in the
			 * document came back as one and the keys lost their bottom bits. The test that
			 * found it asserted on a key that is not exactly representable; one that used a
			 * round number would have passed.
			 */
			try {
				if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
					return Double.valueOf(raw);
				}
				return Long.valueOf(raw);

			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("not a number: " + raw);
			}
		}

		@Nullable
		private Object readLiteral(@NonNull String literal, @Nullable Object value) {

			if (!source.startsWith(literal, at)) {
				throw new IllegalArgumentException("not a value at " + at);
			}

			at += literal.length();
			return value;
		}

		private void expect(char c) {

			skipSpace();

			if (at >= source.length() || source.charAt(at) != c) {
				throw new IllegalArgumentException("expected " + c + " at " + at);
			}
			at++;
		}

		/**
		 * The next character, or a refusal.
		 * <p>
		 * Every read that runs off the end of the document comes through here, so a truncated
		 * body is an {@code IllegalArgumentException} the caller already handles rather than an
		 * index out of bounds it does not -- and a truncated body is what a dropped connection
		 * looks like.
		 */
		private char next(@NonNull String what) {

			if (at >= source.length()) {
				throw new IllegalArgumentException(what);
			}
			return source.charAt(at++);
		}

		private char peek() {
			return at < source.length() ? source.charAt(at) : '\0';
		}

		private void skipSpace() {

			while (at < source.length() && Character.isWhitespace(source.charAt(at))) {
				at++;
			}
		}
	}
}
