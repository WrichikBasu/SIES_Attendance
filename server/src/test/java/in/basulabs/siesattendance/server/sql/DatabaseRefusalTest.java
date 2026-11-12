package in.basulabs.siesattendance.server.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

/** {@link DatabaseRefusal} over the messages PostgreSQL and this schema actually write. */
class DatabaseRefusalTest {

	private static SQLException refusal(String state, String message) {
		return new SQLException(message, state);
	}

	@Test
	void aKnownConstraintIsSaidAsWhatItMeans() {

		SQLException e = refusal("23505", "ERROR: duplicate key value violates unique "
				+ "constraint \"students_email_uq\"\n  Detail: Key (email)=(x@y) already exists.");

		assertEquals("students_email_uq", DatabaseRefusal.constraintOf(e));
		assertEquals("that email address is already another student's",
				DatabaseRefusal.inWords(e));
	}

	@Test
	void anUnknownConstraintIsSaidByItsKind() {

		assertEquals("it is already on the server as another record",
				DatabaseRefusal.inWords(refusal("23505", "ERROR: duplicate key value violates "
						+ "unique constraint \"something_new_uq\"")));
		assertEquals("something it names is not on the server",
				DatabaseRefusal.inWords(refusal("23503", "ERROR: insert or update on table "
						+ "\"x\" violates foreign key constraint \"x_y_fkey\"")));
		assertEquals("one of its values is not allowed",
				DatabaseRefusal.inWords(refusal("23514", "ERROR: new row for relation \"x\" "
						+ "violates check constraint \"x_shape\"")));
	}

	/** A trigger's RAISE was written for a person, names no constraint, and is kept. */
	@Test
	void theSchemasOwnWordsAreKept() {

		SQLException e = refusal("23514", "ERROR: That would leave no administrator; make "
				+ "another one first\n  Where: PL/pgSQL function keep_an_administrator()");

		assertNull(DatabaseRefusal.constraintOf(e));
		assertEquals("That would leave no administrator; make another one first",
				DatabaseRefusal.inWords(e));
	}

	@Test
	void aPolicyAndAMissingValueAreSaidPlainly() {

		assertEquals("it is not yours to change", DatabaseRefusal.inWords(refusal("42501",
				"ERROR: new row violates row-level security policy for table \"courses\"")));
		assertEquals("a value it needs is missing", DatabaseRefusal.inWords(refusal("23502",
				"ERROR: null value in column \"name\" of relation \"students\" violates "
						+ "not-null constraint")));
		assertEquals("the server could not write it",
				DatabaseRefusal.inWords(refusal("XX000", null)));
	}
}
