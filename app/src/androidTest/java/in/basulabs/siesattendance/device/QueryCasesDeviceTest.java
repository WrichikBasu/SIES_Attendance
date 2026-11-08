package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.database.sqlite.SQLiteException;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.EntityAttendance;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityLecture;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.EntityStudentYear;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.pojo.ExportRow;
import in.basulabs.siesattendance.backend.pojo.StatsSummary;
import in.basulabs.siesattendance.backend.stats.StatsFilter;
import in.basulabs.siesattendance.backend.stats.StatsQueryBuilder;
import in.basulabs.siesattendance.backend.testing.QueryCases;
import in.basulabs.siesattendance.backend.util.ExportFilter;
import in.basulabs.siesattendance.backend.util.ExportQueryBuilder;
import in.basulabs.siesattendance.device.support.StagingFiles;

/**
 * Every named query case, run by Room's {@code @RawQuery} methods on this phone's SQLite.
 * <p>
 * {@code SqlBuilderTest} writes these queries out and {@code sql_harness.py} runs them on the
 * desktop's SQLite. The phone's SQLite is a different build, often older, compiled with
 * different options; a function, a window clause or a {@code strftime} modifier that the
 * desktop accepts can be a syntax error here, and the first anybody would hear of it is a
 * statistics screen crashing. The cases are the shared {@link QueryCases}, so a case added for
 * the dump is run here too without anyone remembering to.
 * <p>
 * Two of them are also checked for their answer, on a session small enough to count by hand.
 */
@RunWith(AndroidJUnit4.class)
public class QueryCasesDeviceTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private static final int SESSION = QueryCases.SESSION;

	private String name;

	private MainDatabase room;

	private long course;

	@Before
	public void seedASmallSession() {

		name = StagingFiles.freshName();
		room = MainDatabase.openStaging(StagingFiles.context(), name);

		room.daoAcademicYear().upsert(EntityAcademicYear.of(SESSION,
				LocalDate.of(SESSION, 6, 1), LocalDate.of(SESSION + 1, 5, 31)));

		long prof = room.daoProfessor().insert(new EntityProfessor("Prof P", "p@sies.edu.in",
				Department.PHYSICS, "Professor"));

		EntityCourse c = new EntityCourse("PH-201", "Optics", Stream.BSc, Department.PHYSICS,
				Year.SY, 3, CourseType.MAJOR, null);
		c.profID = prof;
		course = room.daoCourse().insert(c);

		long alice = enrol("ALICE", "PH-1", Division.A);
		long bob = enrol("BOB", "PH-2", Division.B);

		long batch = room.daoBatch().createBatchFromStudents(
				new EntityBatchDesc("PH SY", SESSION, null, Department.PHYSICS),
				List.of(alice, bob));
		room.daoBatch().setCourseLinks(batch, List.of(course));

		ZonedDateTime start = ZonedDateTime.of(SESSION, 7, 14, 9, 0, 0, 0, IST);
		long lecture = room.daoLecture().insert(
				new EntityLecture(course, batch, start, start.plusHours(1), null));

		room.daoAttendance().saveSheet(lecture, List.of(
				new EntityAttendance(lecture, alice, true),
				new EntityAttendance(lecture, bob, false)));
	}

	private long enrol(String student, String roll, Division division) {

		long id = room.daoStudent().insertStudent(new EntityStudent(student, "they/them", null));
		room.daoStudent().insertStudentYear(new EntityStudentYear(id, SESSION, roll, Year.SY,
				Stream.BSc, Department.PHYSICS, null, null, 3, division));
		return id;
	}

	@After
	public void throwItAway() {
		if (room != null) {
			room.close();
		}
		StagingFiles.delete(name);
	}

	@Test
	public void everyStatsCaseRunsOnThisPhonesSqlite() {

		List<String> failures = new ArrayList<>();

		for (QueryCases.StatsCase c : QueryCases.stats()) {
			try {
				if (c.summary) {
					room.daoStats().runSummary(StatsQueryBuilder.summary(c.filter));
				} else {
					room.daoStats().runGrouped(StatsQueryBuilder.grouped(c.filter));
				}
			} catch (SQLiteException | IllegalArgumentException e) {
				failures.add(c.name + ": " + e.getMessage());
			}
		}
		if (!failures.isEmpty()) {
			fail(String.join("\n", failures));
		}
	}

	@Test
	public void everyExportCaseRunsOnThisPhonesSqlite() {

		List<String> failures = new ArrayList<>();

		for (QueryCases.ExportCase c : QueryCases.exports()) {
			try {
				room.daoAttendance().getExportRowsSync(
						ExportQueryBuilder.build(c.academicYear, c.filter));
			} catch (SQLiteException | IllegalArgumentException e) {
				failures.add(c.name + ": " + e.getMessage());
			}
		}
		if (!failures.isEmpty()) {
			fail(String.join("\n", failures));
		}
	}

	@Test
	public void theSessionSummaryCountsWhatWasMarked() {

		StatsSummary summary = room.daoStats().runSummary(
				StatsQueryBuilder.summary(new StatsFilter(SESSION)));

		assertNotNull(summary);
		assertEquals("marks", 2, summary.total);
		assertEquals("present", 1, summary.attended);
		assertEquals(1, summary.lectures);
		assertEquals(2, summary.students);
		assertEquals(1, summary.courses);
	}

	@Test
	public void theUnrestrictedExportHasOneRowPerStudentForTheCourse() {

		List<ExportRow> rows = room.daoAttendance().getExportRowsSync(
				ExportQueryBuilder.build(SESSION, new ExportFilter()));

		assertEquals(2, rows.size());

		int attended = 0;
		for (ExportRow row : rows) {
			assertEquals(course, row.courseID);
			assertEquals(1, row.held);
			attended += row.attended;
		}
		assertEquals(1, attended);
	}
}
