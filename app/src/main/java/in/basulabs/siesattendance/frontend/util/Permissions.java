package in.basulabs.siesattendance.frontend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.sync.SyncClient;

/**
 * What the signed-in account may change on this phone, and the one place that decides it.
 * <p>
 * A class representative signs in to mark registers — that is what an
 * {@code attendance_delegate} grant is for, and it is the whole reason a student has an account
 * at all. It is not a reason to let them add a professor, rename a course or edit somebody's
 * roll number. So the screens that change the college's own records are not offered to a student
 * account.
 *
 * <h3>The server decides; this stops the app offering what will be refused</h3>
 * The authority is in SQL and stays there: {@code students}, {@code professors} and
 * {@code courses} are policed by row-level security, and a push of a row a student had no
 * business writing comes back refused with a sentence saying so. This class exists because a
 * button that leads to a refusal three steps later is a bad button — and because a roster edited
 * on a phone that can never push it is a roster that silently disagrees with the office's.
 * <p>
 * It is therefore <em>not</em> a security boundary and must never be treated as one. Anybody who
 * can modify the app can modify this. What makes the rule real is the policy on the table.
 *
 * <h3>What a student keeps</h3>
 * Everything about attendance: taking a register, looking at one, the statistics, the exports,
 * and reading the lists. Reading is deliberate — a CR marking a roll has to see who is on it.
 *
 * <h3>Which courses a student sees</h3>
 * Only the ones they are a class representative of. A phone holds whatever courses reached it —
 * a restored backup, a professor's data on a phone handed on — and a CR shown all of them could
 * open a register for any, mark it, and learn only at the next sync that the server refused every
 * mark. {@link #visibleCourses} narrows every course list a screen is given to the appointments
 * {@code /me} reported, which the app asks for at sign-in and again on every sync.
 *
 * <h3>Which batches a student sees</h3>
 * Only the ones linked to a course they represent, by {@link #visibleBatches}: a batch is a list
 * of students, and a class representative has no business with the list for a course they do
 * not mark. The server narrows what it sends the same way (V24), with one addition the screens
 * do not need, which V24 explains.
 *
 * <h3>When nothing is known</h3>
 * Permitted, for the same reason {@code SignInGuard} lets a build with no server through: an
 * install that signed in before this build recorded anything, and a build with no server to sign
 * in to at all, would otherwise lose the ability to do anything. The next sign-in records both
 * facts and the rule applies from then on.
 * <p>
 * Courses are the exception. A student account with no recorded appointments is shown no course
 * rather than every course: being shown every course is the defect that rule exists to stop, and
 * the next sync records the appointments within minutes.
 */
public final class Permissions {

	/** The things an account may or may not change. */
	public enum Capability {

		/** Adding a student, correcting one, or removing one from a roll. */
		STUDENTS,

		/**
		 * Adding a professor or correcting one: the office's and an administrator's, and not a
		 * professor's, not even a head's (V35).
		 */
		PROFESSORS,

		/** Adding a course, correcting one, or marking one discontinued. */
		COURSES,

		/**
		 * Making a batch, changing who is in it and which courses it is for, or removing it:
		 * a professor's, or an administrator's. Which batches is {@link #mayKeepBatchesOf}'s
		 * question, since a batch belongs to a department and only that department's own
		 * professors keep it (V33).
		 */
		BATCHES,

		/**
		 * Adding an academic session. An administrator's alone: a session is the calendar
		 * every register in the college is filed against, and the server refuses anybody
		 * else's (V25).
		 */
		NEW_SESSION,

		/** Correcting a session's dates, or removing one: the office's and an administrator's. */
		SESSIONS,

		/**
		 * Importing students from a CSV: a professor and up -- heads, the office and
		 * administrators -- and never a student. A professor's rows are held to their own
		 * department (V27), which the import checks row by row.
		 */
		IMPORT_STUDENTS
	}

	private Permissions() {
	}

	/**
	 * Whether this account may change that.
	 * <p>
	 * Two facts, and the second is the server's own answer rather than this app's guess.
	 * {@code mayKeepARoll} is {@code /me}'s, from {@code actor_may_keep_a_roll()}: the office,
	 * an administrator, or the head of a department. Before it existed this class asked only
	 * the account kind, which let every professor edit the roll locally — and the server then
	 * refused the push, so a professor's correction lived on one phone and nowhere else. Asking
	 * what the server will accept is the only way the two copies cannot start to disagree.
	 * <p>
	 * A head is offered the screens and is still refused another department's student, row by
	 * row, by the policies. That is not a gap this class could close: it would have to know
	 * which department every student is in, and the enrolment that says so is exactly what a
	 * roll is. The refusal is reported now, which is what makes offering the screen honest.
	 *
	 * @param who          the signed-in account's kind, or null when none was recorded
	 * @param mayKeepARoll what {@code /me} said, or the default for an install that never
	 *                     asked
	 * @param what         the thing being changed
	 */
	public static boolean may(@Nullable AccountKind who, boolean mayKeepARoll,
	                          @NonNull Capability what) {
		return may(who, mayKeepARoll, null, what);
	}

	/**
	 * The same question, for the capabilities that belong to a role rather than to an account's
	 * kind.
	 *
	 * @param roles the roles {@code /me} last reported ({@code ADMIN}, {@code OFFICE}, ...), or
	 *              null when they were never recorded -- which, for an account that is known,
	 *              refuses the role's capabilities until the next sync records them
	 */
	public static boolean may(@Nullable AccountKind who, boolean mayKeepARoll,
	                          @Nullable Set<String> roles, @NonNull Capability what) {

		// Unknown is permitted, for the reason in the class notes: a build with no server has
		// nobody to be an administrator, and must still be able to set up its own calendar.
		if (who == null && (what == Capability.NEW_SESSION || what == Capability.SESSIONS)) {
			return true;
		}

		boolean admin = roles != null && roles.contains("ADMIN");
		boolean office = roles != null && roles.contains("OFFICE");

		// A student holds no role that keeps a roll, so this is belt and braces -- and it is
		// the half that does not depend on having reached the server.
		if (who == AccountKind.STUDENT) {
			return false;
		}

		return switch (what) {
			// The roster screens a head keeps for their department.
			case STUDENTS, COURSES -> mayKeepARoll;

			// The staff list is the office's: no professor changes another, or their own
			// department. Unknown is permitted for the reason in the class notes.
			case PROFESSORS -> who == null || admin || office;

			// A professor or an administrator, not the office. Unknown is permitted for the
			// reason in the class notes.
			case BATCHES -> who == null || who == AccountKind.PROFESSOR || admin;

			case NEW_SESSION -> admin;
			case SESSIONS -> admin || office;
			case IMPORT_STUDENTS -> who == null || who == AccountKind.PROFESSOR || mayKeepARoll;
		};
	}

	/**
	 * Whether this account may make, change or remove a batch of that department.
	 * <p>
	 * A professor of the department or an administrator, as {@code /me} reported them. A batch with no department yet is an administrator's alone to place. When nothing
	 * was ever recorded, any professor may, for the reason in the class notes.
	 *
	 * @param keeps the departments {@code /me} last said this account keeps batches for, with
	 *              {@link SyncClient#ANY_DEPARTMENT} alone for an administrator, or null when it
	 *              never said
	 */
	public static boolean mayKeepBatchesOf(@Nullable AccountKind who, @Nullable Set<String> keeps,
	                                       @Nullable Department department) {

		if (who == AccountKind.STUDENT) {
			return false;
		}

		if (keeps == null) {
			return who == null || who == AccountKind.PROFESSOR;
		}

		return keeps.contains(SyncClient.ANY_DEPARTMENT)
				|| (department != null && keeps.contains(department.name()));
	}

	/**
	 * Whether this account may rename that batch and change who is in it.
	 * <p>
	 * Whoever keeps its department's batches, by {@link #mayKeepBatchesOf}; and a professor who
	 * coordinates every course the batch serves, as {@code /me} last listed those batches (V42),
	 * which is how a value education course's coordinator from another department arranges its
	 * batches. Making, removing and linking a batch stay with its department.
	 *
	 * @param coordinated the batches {@code /me} last said this account coordinates, or null
	 *                    when it never said
	 */
	public static boolean mayEditBatch(@Nullable AccountKind who, @Nullable Set<String> keeps,
	                                   @Nullable Set<Long> coordinated,
	                                   @NonNull EntityBatchDesc batch) {
		return mayKeepBatchesOf(who, keeps, batch.department)
				|| (who == AccountKind.PROFESSOR && batch.department != null
				    && coordinated != null && coordinated.contains(batch.batchID));
	}

	/**
	 * Whether this account may appoint that course's class representatives: its instructor,
	 * the head of the department offering it, or an administrator, as {@code /me} last listed
	 * the courses. Never a student. Refused when nothing was ever recorded, unlike the rest of
	 * this class: an appointment is made on the server and nowhere else, so an install that
	 * has not asked has nothing to make it with, and the next sync records the list.
	 *
	 * @param appoints the course ids {@code /me} last said this account may appoint for, or
	 *                 null when it never said
	 */
	public static boolean mayAppointFor(@Nullable AccountKind who, @Nullable Set<Long> appoints,
	                                    long courseId) {
		return who != AccountKind.STUDENT && appoints != null && appoints.contains(courseId);
	}

	/**
	 * The departments this account may make a batch for, in the order to offer them: every
	 * department for an administrator, and for anybody nothing was ever recorded about;
	 * otherwise a professor's own, alone.
	 */
	@NonNull
	public static List<Department> batchDepartments(@Nullable AccountKind who,
	                                                @Nullable Set<String> keeps) {

		List<Department> departments = new ArrayList<>();

		for (Department department : Department.values()) {
			if (keeps == null ? who != AccountKind.STUDENT
					: mayKeepBatchesOf(who, keeps, department)) {
				departments.add(department);
			}
		}
		return departments;
	}

	/**
	 * The batches out of that list this account is shown.
	 * <p>
	 * Every batch for anybody but a student. For a student, the ones linked to a course they
	 * represent, in the order given; none when no appointment has been recorded, for the same
	 * reason as {@link #visibleCourses}.
	 *
	 * @param linked the batches linked to the courses this account represents, or null when its
	 *               appointments have never been recorded
	 */
	@NonNull
	public static List<EntityBatchDesc> visibleBatches(@Nullable AccountKind who,
	                                                  @Nullable Set<Long> linked,
	                                                  @NonNull List<EntityBatchDesc> batches) {

		if (who != AccountKind.STUDENT) {
			return batches;
		}

		List<EntityBatchDesc> visible = new ArrayList<>();

		if (linked == null) {
			return visible;
		}

		for (EntityBatchDesc batch : batches) {
			if (linked.contains(batch.batchID)) {
				visible.add(batch);
			}
		}
		return visible;
	}

	/**
	 * The batches out of that list a professor browses: their own department's, and the ones
	 * linked to a course they teach or head, whoever's department made them -- a value education
	 * course's batches are its offering department's, and every professor teaching it marks
	 * them. The same two arms as the server's (V32). Everybody else's list is returned as it is,
	 * as is a professor's when {@code /me} never said which departments are theirs.
	 * <p>
	 * For the lists a professor looks through -- the batches tab, the filters -- and not for
	 * the register. A professor who teaches another department's course takes its attendance
	 * from that department's batches, which the server sends for exactly that, and the register
	 * narrows to the course's department itself.
	 *
	 * @param keeps  the departments {@code /me} last said this account keeps batches for
	 * @param linked the batches linked to the courses {@code /me} last said this account may
	 *               appoint a class representative for, which are the ones it teaches or heads;
	 *               null when that was never said
	 */
	@NonNull
	public static List<EntityBatchDesc> browsedBatches(@Nullable AccountKind who,
	                                                   @Nullable Set<String> keeps,
	                                                   @Nullable Set<Long> linked,
	                                                   @NonNull List<EntityBatchDesc> batches) {

		if (who != AccountKind.PROFESSOR || keeps == null
				|| keeps.contains(SyncClient.ANY_DEPARTMENT)) {
			return batches;
		}

		List<EntityBatchDesc> browsed = new ArrayList<>();

		for (EntityBatchDesc batch : batches) {
			if ((batch.department != null && keeps.contains(batch.department.name()))
					|| (linked != null && linked.contains(batch.batchID))) {
				browsed.add(batch);
			}
		}
		return browsed;
	}

	/**
	 * The professors out of that list a professor is shown on the Professors tab: their own
	 * department's (V35). The phone may hold others -- whoever coordinates or teaches a course
	 * it holds -- and those stay for the course screens that name them. Everybody else's list
	 * is returned as it is, as is a professor's when {@code /me} never said which department
	 * is theirs.
	 *
	 * @param own the departments {@code /me} last said this account keeps batches for, which
	 *            for a professor is their own department alone (V33)
	 */
	@NonNull
	public static List<EntityProfessor> ownDepartmentsProfessors(
			@Nullable AccountKind who, @Nullable Set<String> own,
			@NonNull List<EntityProfessor> professors) {

		if (who != AccountKind.PROFESSOR || own == null
				|| own.contains(SyncClient.ANY_DEPARTMENT)) {
			return professors;
		}

		List<EntityProfessor> colleagues = new ArrayList<>();

		for (EntityProfessor professor : professors) {
			if (professor.department != null && own.contains(professor.department.name())) {
				colleagues.add(professor);
			}
		}
		return colleagues;
	}

	/**
	 * The courses out of that list this account is shown.
	 * <p>
	 * Every course for anybody but a student. For a student, the ones they represent, in the
	 * order they were given; none at all when no appointment has been recorded. Not by session:
	 * an appointment is for one session, and a register for a lecture in another is still the
	 * server's to refuse.
	 *
	 * @param represents the course ids {@code /me} last reported, or null when it never has
	 */
	@NonNull
	public static List<EntityCourse> visibleCourses(@Nullable AccountKind who,
	                                                @Nullable Set<Long> represents,
	                                                @NonNull List<EntityCourse> courses) {

		if (who != AccountKind.STUDENT) {
			return courses;
		}

		List<EntityCourse> visible = new ArrayList<>();

		if (represents == null) {
			return visible;
		}

		for (EntityCourse course : courses) {
			if (represents.contains(course.courseID)) {
				visible.add(course);
			}
		}
		return visible;
	}
}
