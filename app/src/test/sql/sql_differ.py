#!/usr/bin/env python3
"""
Runs the app's SQL and the server's SQL side by side and compares the rows.

The acceptance gate for Phase 2. Two query builders now answer the same questions in two
dialects: StatsQueryBuilder and ExportQueryBuilder against SQLite on the device,
PgStatsQueryBuilder and PgExportQueryBuilder against PostgreSQL on the server. They are
string-assembled, which is the riskiest kind of translation there is, and reading them side
by side proves nothing. So neither is trusted: both are executed.

  - The statements come from the builders themselves, dumped by SqlBuilderTest into
    app/build/wb-sql/ and by SqlDumpTest into server/build/wb-sql-pg/. Both dumps are
    generated from one shared list of named filters, QueryCases, so the two sides cannot
    be answering different questions.
  - The SQLite schema comes from Room's own generated MainDatabase_Impl, so it is
    byte-for-byte what a device creates.
  - The PostgreSQL schema comes from V1__baseline.sql and V2__constraints.sql, applied to a
    throwaway container exactly as tools/verify-schema.sh applies them.
  - The fixture below is defined once, in neutral form, and rendered into both. The same
    data, not a second approximation of it.

Nothing here hand-writes an expected answer. Every check is "the two engines returned the
same rows", which is the only claim worth making about a translation.

Requires Docker. Needs no Python packages: PostgreSQL is reached through psql in the
container, with each argument passed as a psql variable so that values stay out of the
statement text.

    python3 app/src/test/sql/sql_differ.py
    python3 app/src/test/sql/sql_differ.py --only stats_grouped_WEEK --keep
"""

from __future__ import annotations

import argparse
import csv
import io
import os
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

# The zone the college is in, and the zone every fixture timestamp is recorded in.
#
# It is set as the process zone before sqlite3 is used, which is not cosmetic. SQLite has
# no per-row zone to work from: the app's queries call strftime(..., 'localtime'), which
# reads the zone of whoever is running the query. PostgreSQL reads lectures.start_zone, the
# zone the lecture was actually recorded in. The two agree only when the reader happens to
# be in the deployment zone, so the comparison is run under that assumption — and
# check_the_zone_difference_is_real below demonstrates what happens when it does not hold,
# which is the whole reason the zone column exists.
DEPLOYMENT_ZONE = "Asia/Kolkata"
os.environ["TZ"] = DEPLOYMENT_ZONE
time.tzset()

import sqlite3  # noqa: E402  (after tzset, deliberately)

HERE = os.path.dirname(os.path.abspath(__file__))
# app/src/test/sql -> the repository root.
PROJECT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))

sys.path.insert(0, HERE)
import sql_harness  # noqa: E402  (the Room DDL reader and timestamp helpers)

SQLITE_DUMPS = os.path.join(PROJECT, "app/build/wb-sql")
POSTGRES_DUMPS = os.path.join(PROJECT, "server/build/wb-sql-pg")
MIGRATIONS = os.path.join(PROJECT, "server/src/main/resources/db/migration")

CONTAINER = "sies-sql-differ"
DEFAULT_IMAGE = "postgres:18"

IST = timezone(timedelta(hours=5, minutes=30))

# How psql prints a NULL inside the COPY below, and what a SQLite None is turned into, so
# that the two are compared as the same thing and neither is confused with an empty string.
NULL = "\\N"

failures: list[tuple[str, str]] = []
passes: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        passes.append(name)
    else:
        failures.append((name, detail))


# ── The fixture, in neutral form ─────────────────────────────────────────
#
# Defined once and rendered into both engines. It mirrors the one in sql_harness.py, with
# two deliberate changes.
#
# First, it is valid under V2__constraints.sql. The harness fixture offers a Mathematics
# minor and an English open elective to a BSc_unaided class, which the schema now rejects
# and which FragmentAddCourse would never have let anyone create: a course's stream must be
# one its offering department actually runs. The minor and the open elective here come from
# Information Technology and Environmental Science, which do run BSc_unaided, so the roster
# join's MINOR and OE branches are still exercised with data the app would accept.
#
# Second, it carries five extra lectures around the turn of the year and one just after
# midnight, because those are the dates where the two dialects' date arithmetic could
# disagree and nothing else would notice.


@dataclass(frozen=True)
class Session:
    year: int
    start: tuple[int, int, int]
    end: tuple[int, int, int]


@dataclass(frozen=True)
class Professor:
    prof_id: int
    name: str
    email: str
    department: str
    designation: str


@dataclass(frozen=True)
class Student:
    student_id: int
    name: str
    pronouns: str
    email: str | None
    apaar_id: int | None = None


@dataclass(frozen=True)
class Enrolment:
    student_id: int
    academic_year: int
    roll_no: str
    year: str
    stream: str
    major_dept: str
    minor_dept: str | None
    oe_dept: str | None
    semester: int | None
    division: str | None
    # Null means the start of the session, and not left.
    join_date: tuple[int, int, int] | None = None
    leave_date: tuple[int, int, int] | None = None


@dataclass(frozen=True)
class Course:
    course_id: int
    prof_id: int
    code: str
    topic: str
    stream: str
    offering_department: str
    year: str
    semester: int
    course_type: str
    remarks: str | None = None
    date_introduced: tuple[int, int, int, int, int] | None = None
    date_discontinued: tuple[int, int, int, int, int] | None = None


@dataclass(frozen=True)
class Batch:
    batch_id: int
    name: str
    description: str | None
    academic_year: int
    members: tuple[int, ...] = ()


@dataclass(frozen=True)
class Lecture:
    lecture_id: int
    course_id: int
    batch_id: int | None
    start: tuple[int, int, int, int, int]
    end: tuple[int, int, int, int, int]
    notes: str | None = None
    why: str = ""


@dataclass(frozen=True)
class Mark:
    lecture_id: int
    student_id: int
    present: bool


@dataclass(frozen=True)
class Fixture:
    sessions: list[Session] = field(default_factory=list)
    professors: list[Professor] = field(default_factory=list)
    students: list[Student] = field(default_factory=list)
    enrolments: list[Enrolment] = field(default_factory=list)
    courses: list[Course] = field(default_factory=list)
    batches: list[Batch] = field(default_factory=list)
    # (course_id, batch_id): a course with a batch linked for the session takes its roll
    # from the batch (V23), and one without falls back to the course-type rules.
    course_batches: list[tuple[int, int]] = field(default_factory=list)
    lectures: list[Lecture] = field(default_factory=list)
    marks: list[Mark] = field(default_factory=list)


FIXTURE = Fixture(
    sessions=[
        Session(2024, (2024, 6, 1), (2025, 5, 31)),
        Session(2025, (2025, 6, 1), (2026, 5, 31)),
    ],
    professors=[
        Professor(1, "DR SHARMA", "sharma@sies.edu.in", "COMPUTER_SCIENCE",
                  "Assistant Professor"),
        Professor(2, "DR RAO", "rao@sies.edu.in", "PHYSICS", "Associate Professor"),
    ],
    students=[
        Student(1, "AARAV PATIL", "he/him", "aarav@sies.edu.in", apaar_id=123456789012),
        Student(2, "SNEHA MEHTA", "she/her", "sneha@sies.edu.in"),
        # No email: the column is nullable and a projection that drops the row would be a
        # defect worth catching on both engines.
        Student(3, "RAHUL DESAI", "he/him", None),
        Student(4, "MEERA IYER", "she/her", "meera@sies.edu.in"),
        Student(5, "PRIYA NAIR", "she/her", "priya@sies.edu.in"),
    ],
    enrolments=[
        Enrolment(1, 2025, "2025CS01", "SY", "BSc_unaided", "COMPUTER_SCIENCE",
                  "INFORMATION_TECHNOLOGY", "ENVIRONMENTAL_SCIENCE", 3, None),
        # Joined late, after the first two lectures she was marked on: they must not count.
        Enrolment(2, 2025, "2025CS02", "SY", "BSc_unaided", "COMPUTER_SCIENCE",
                  None, "ENVIRONMENTAL_SCIENCE", 3, None, join_date=(2025, 7, 12)),
        # Left: off the register export, though the marks before stay in the statistics.
        Enrolment(3, 2025, "2025CS03", "SY", "BSc_unaided", "COMPUTER_SCIENCE",
                  None, None, 3, None, leave_date=(2025, 12, 1)),
        # The only students on a stream that has divisions at all, and in two different
        # divisions of it, so the CLASS grouping has something to split.
        Enrolment(4, 2025, "2025PH01", "SY", "BSc", "PHYSICS",
                  "COMPUTER_SCIENCE", "HINDI", 3, "B"),
        Enrolment(5, 2025, "2025PH02", "SY", "BSc", "PHYSICS", None, None, 3, "C"),
        # The same student a year earlier, under a different roll number and class. This is
        # what makes a report for a past session read correctly after a promotion.
        Enrolment(4, 2024, "2024PH07", "FY", "BSc", "PHYSICS", None, None, 1, "A"),
    ],
    courses=[
        Course(1, 1, "CS201", "Data Structures", "BSc_unaided", "COMPUTER_SCIENCE",
               "SY", 3, "MAJOR"),
        Course(2, 1, "CS202", "DS Practicals", "BSc_unaided", "COMPUTER_SCIENCE",
               "SY", 3, "MAJOR_PRACTICALS"),
        Course(3, 1, "IT201", "Discrete Maths", "BSc_unaided", "INFORMATION_TECHNOLOGY",
               "SY", 3, "MINOR"),
        Course(4, 1, "EV201", "Ecology", "BSc_unaided", "ENVIRONMENTAL_SCIENCE",
               "SY", 3, "OE"),
        # IKS is taught to the whole year and stream regardless of department.
        Course(5, 1, "IK201", "Indian Knowledge", "BSc_unaided", "COMPUTER_SCIENCE",
               "SY", 3, "IKS"),
        # Discontinued, and still on the roll: an export has to keep showing a course that
        # ran for part of the session.
        Course(6, 1, "CS900", "Retired Course", "BSc_unaided", "COMPUTER_SCIENCE",
               "SY", 4, "MAJOR", None, None, (2025, 6, 30, 12, 0)),
        Course(7, 2, "PH201", "Mechanics", "BSc", "PHYSICS", "SY", 3, "MAJOR"),
    ],
    batches=[
        Batch(1, "Batch A", None, 2025, (1, 3)),
        Batch(2, "Batch B", None, 2025, (2,)),
        # A batch inside a batch, with no lecture of its own. Its student sits Batch A's.
        Batch(3, "Batch A1", None, 2025, (1,)),
        # The students who chose the Ecology elective: one the rules would also have put
        # there, and a Physics BSc student no rule could have, being in another stream.
        Batch(4, "Ecology takers", None, 2025, (1, 4)),
    ],
    course_batches=[
        # EV201 takes its roll from the batch, so Sneha (OE ENVIRONMENTAL_SCIENCE, not in
        # the batch) leaves its roll and Meera (Physics, BSc) joins it.
        (4, 4),
    ],
    lectures=[
        Lecture(1, 1, None, (2025, 7, 10, 10, 0), (2025, 7, 10, 11, 0)),
        Lecture(2, 1, None, (2025, 7, 11, 10, 0), (2025, 7, 11, 11, 0)),
        Lecture(3, 2, 1, (2025, 7, 12, 14, 0), (2025, 7, 12, 16, 0)),
        Lecture(4, 2, 2, (2025, 7, 12, 16, 0), (2025, 7, 12, 18, 0)),
        Lecture(5, 1, None, (2025, 8, 15, 10, 0), (2025, 8, 15, 11, 0)),
        Lecture(6, 1, None, (2024, 2, 2, 10, 0), (2024, 2, 2, 11, 0), "orphan",
                why="outside every defined session; both engines must drop it"),
        Lecture(7, 7, None, (2025, 7, 10, 12, 0), (2025, 7, 10, 13, 0),
                why="the divided Physics class"),
        # Four dates around the turn of the year. SQLite numbers weeks from the first
        # Monday, with the days before it in week 00, and PostgreSQL has no function that
        # means the same thing, so the rule is spelled out in PostgresStatsDialect and
        # checked here on both sides of both boundaries.
        Lecture(8, 1, None, (2025, 12, 29, 9, 0), (2025, 12, 29, 10, 0),
                why="Monday, the last whole week of 2025"),
        Lecture(9, 1, None, (2026, 1, 1, 9, 0), (2026, 1, 1, 10, 0),
                why="a Thursday 1 January: week 00, not week 01"),
        Lecture(10, 1, None, (2026, 1, 4, 9, 0), (2026, 1, 4, 10, 0),
                why="the Sunday before the first Monday: still week 00"),
        Lecture(11, 1, None, (2026, 1, 5, 9, 0), (2026, 1, 5, 10, 0),
                why="the first Monday: week 01"),
        # 01:00 in Asia/Kolkata is 19:30 the previous day in UTC, so this lecture is in a
        # different month depending on which zone the question is asked in. See
        # check_the_zone_difference_is_real.
        Lecture(12, 1, None, (2025, 10, 1, 1, 0), (2025, 10, 1, 2, 0),
                why="just after midnight: a different month in UTC"),
    ],
    marks=[
        Mark(1, 1, True), Mark(1, 2, True), Mark(1, 3, False),
        # Rahul is left off this sheet entirely, which is not the same as being absent.
        Mark(2, 1, True), Mark(2, 2, False),
        Mark(3, 1, True), Mark(3, 3, True),
        Mark(4, 2, False),
        Mark(5, 1, True), Mark(5, 2, True), Mark(5, 3, True),
        # A mark on the orphan lecture, so that dropping it is actually tested: the
        # statistics queries start from attendance, and an orphan with no marks would fall
        # out for the wrong reason.
        Mark(6, 1, True),
        Mark(7, 4, True), Mark(7, 5, False),
        Mark(8, 1, True), Mark(8, 2, False), Mark(8, 3, True),
        Mark(9, 1, True), Mark(9, 2, True),
        Mark(10, 1, False), Mark(10, 2, True),
        Mark(11, 1, True), Mark(11, 3, True),
        Mark(12, 1, True), Mark(12, 2, False),
    ],
)


def epoch_day(day: tuple[int, int, int] | None) -> int | None:
    """A date as the device stores it: days since 1970-01-01."""
    if day is None:
        return None
    from datetime import date
    return (date(*day) - date(1970, 1, 1)).days


def iso_date(day: tuple[int, int, int] | None) -> str | None:
    """The same date as text PostgreSQL parses."""
    if day is None:
        return None
    return "%04d-%02d-%02d" % day


def ts(stamp: tuple[int, int, int, int, int]) -> str:
    """A timestamp as the app stores it: "epochSecond|nano|zone"."""
    year, month, day, hour, minute = stamp
    return sql_harness.ts(year, month, day, hour, minute, DEPLOYMENT_ZONE)


def iso(stamp: tuple[int, int, int, int, int]) -> str:
    """The same instant as text PostgreSQL parses, with the offset spelled out."""
    year, month, day, hour, minute = stamp
    return datetime(year, month, day, hour, minute, tzinfo=IST).isoformat()


# ── Rendering the fixture ────────────────────────────────────────────────


def build_sqlite() -> sqlite3.Connection:
    """
    The fixture in SQLite, on Room's own schema.

    Rows are inserted positionally, as sql_harness does, because the column order is
    Room's and reading it back out of the generated DDL to name every column would be a
    second thing to keep in step. A column added to an entity breaks this loudly.
    """
    conn = sqlite3.connect(":memory:")
    conn.execute("PRAGMA foreign_keys = ON")
    for statement in sql_harness.load_ddl():
        conn.execute(statement)

    c = conn.cursor()

    for session in FIXTURE.sessions:
        # academicYear, startDate, endDate (epoch day), startEpoch, endEpoch
        c.execute("INSERT INTO academic_years VALUES (?,?,?,?,?)", (
            session.year, 0, 0,
            sql_harness.start_of_day(*session.start),
            sql_harness.end_of_day(*session.end),
        ))

    for professor in FIXTURE.professors:
        c.execute("INSERT INTO professors VALUES (?,?,?,?,?)", (
            professor.prof_id, professor.name, professor.email,
            professor.department, professor.designation,
        ))

    for student in FIXTURE.students:
        # Named rather than positional: Room orders columns by the entity's fields, and a
        # field added in the middle would otherwise shift every value after it.
        c.execute("INSERT INTO students (studentID, name, pronouns, email, apaarId) "
                  "VALUES (?,?,?,?,?)", (
            student.student_id, student.name, student.pronouns, student.email,
            student.apaar_id,
        ))

    for enrolment in FIXTURE.enrolments:
        c.execute("INSERT INTO student_year (studentID, academicYear, rollNo, year, stream, "
                  "majorDept, minorDept, oeDept, semester, division, joinDate, leaveDate) "
                  "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", (
            enrolment.student_id, enrolment.academic_year, enrolment.roll_no,
            enrolment.year, enrolment.stream, enrolment.major_dept,
            enrolment.minor_dept, enrolment.oe_dept, enrolment.semester,
            enrolment.division, epoch_day(enrolment.join_date),
            epoch_day(enrolment.leave_date),
        ))

    for course in FIXTURE.courses:
        c.execute("INSERT INTO courses VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", (
            course.course_id, course.prof_id, course.code, course.topic, course.stream,
            course.offering_department, course.year, course.semester, course.course_type,
            course.remarks,
            None if course.date_introduced is None else ts(course.date_introduced),
            None if course.date_discontinued is None else ts(course.date_discontinued),
        ))

    for batch in FIXTURE.batches:
        c.execute("INSERT INTO batch_desc VALUES (?,?,?,?)", (
            batch.batch_id, batch.name, batch.description, batch.academic_year,
        ))
        for student_id in batch.members:
            c.execute("INSERT INTO batch_student_map VALUES (?,?,?)",
                      (batch.batch_id, student_id, batch.academic_year))

    for course_id, batch_id in FIXTURE.course_batches:
        c.execute("INSERT INTO course_batch_map VALUES (?,?)", (course_id, batch_id))

    for lecture in FIXTURE.lectures:
        c.execute("INSERT INTO lectures VALUES (?,?,?,?,?,?)", (
            lecture.lecture_id, lecture.course_id, lecture.batch_id,
            ts(lecture.start), ts(lecture.end), lecture.notes,
        ))

    for mark in FIXTURE.marks:
        c.execute("INSERT INTO attendance VALUES (?,?,?)",
                  (mark.lecture_id, mark.student_id, 1 if mark.present else 0))

    conn.commit()
    return conn


def postgres_inserts() -> list[tuple[str, list[str | None]]]:
    """
    The fixture as PostgreSQL statements, with every value a parameter.

    Columns are named here, unlike the SQLite side: the server's tables carry the
    provenance columns the device schema has no equivalent for, and every one of them is
    deliberately left NULL. These rows stand for device history, and history has no author.
    """
    out: list[tuple[str, list[str | None]]] = []

    for session in FIXTURE.sessions:
        out.append((
            "INSERT INTO academic_years (academic_year, start_date, end_date) "
            "VALUES (?, ?::date, ?::date)",
            [str(session.year),
             "%04d-%02d-%02d" % session.start,
             "%04d-%02d-%02d" % session.end],
        ))

    for professor in FIXTURE.professors:
        out.append((
            "INSERT INTO professors (prof_id, name, email, department, designation) "
            "VALUES (?, ?, ?, ?::department, ?)",
            [str(professor.prof_id), professor.name, professor.email,
             professor.department, professor.designation],
        ))

    for student in FIXTURE.students:
        out.append((
            "INSERT INTO students (student_id, name, pronouns, email, apaar_id) "
            "VALUES (?, ?, ?, ?, ?::bigint)",
            [str(student.student_id), student.name, student.pronouns, student.email,
             None if student.apaar_id is None else str(student.apaar_id)],
        ))

    for enrolment in FIXTURE.enrolments:
        out.append((
            "INSERT INTO student_year (student_id, academic_year, roll_no, year, stream, "
            "major_dept, minor_dept, oe_dept, semester, division, join_date, leave_date) "
            "VALUES (?, ?, ?, ?::study_year, ?::degree_stream, ?::department, "
            "?::department, ?::department, ?, ?::class_division, ?::date, ?::date)",
            [str(enrolment.student_id), str(enrolment.academic_year), enrolment.roll_no,
             enrolment.year, enrolment.stream, enrolment.major_dept,
             enrolment.minor_dept, enrolment.oe_dept,
             None if enrolment.semester is None else str(enrolment.semester),
             enrolment.division, iso_date(enrolment.join_date),
             iso_date(enrolment.leave_date)],
        ))

    for course in FIXTURE.courses:
        out.append((
            "INSERT INTO courses (course_id, prof_id, course_code, course_topic, stream, "
            "offering_department, year, semester, course_type, remarks, "
            "date_introduced, date_introduced_zone, "
            "date_discontinued, date_discontinued_zone) "
            "VALUES (?, ?, ?, ?, ?::degree_stream, ?::department, ?::study_year, ?, "
            "?::course_type, ?, ?::timestamptz, ?, ?::timestamptz, ?)",
            [str(course.course_id), str(course.prof_id), course.code, course.topic,
             course.stream, course.offering_department, course.year,
             str(course.semester), course.course_type, course.remarks,
             None if course.date_introduced is None else iso(course.date_introduced),
             None if course.date_introduced is None else DEPLOYMENT_ZONE,
             None if course.date_discontinued is None else iso(course.date_discontinued),
             None if course.date_discontinued is None else DEPLOYMENT_ZONE],
        ))

    for batch in FIXTURE.batches:
        out.append((
            "INSERT INTO batch_desc (batch_id, batch_name, batch_desc, academic_year) "
            "VALUES (?, ?, ?, ?)",
            [str(batch.batch_id), batch.name, batch.description,
             str(batch.academic_year)],
        ))
        for student_id in batch.members:
            out.append((
                "INSERT INTO batch_student_map (batch_id, student_id, academic_year) "
                "VALUES (?, ?, ?)",
                [str(batch.batch_id), str(student_id), str(batch.academic_year)],
            ))

    for course_id, batch_id in FIXTURE.course_batches:
        out.append((
            "INSERT INTO course_batch_map (course_id, batch_id) VALUES (?, ?)",
            [str(course_id), str(batch_id)],
        ))

    for lecture in FIXTURE.lectures:
        out.append((
            "INSERT INTO lectures (lecture_id, course_id, batch_id, "
            "start_at, start_zone, end_at, end_zone, notes) "
            "VALUES (?, ?, ?, ?::timestamptz, ?, ?::timestamptz, ?, ?)",
            [str(lecture.lecture_id), str(lecture.course_id),
             None if lecture.batch_id is None else str(lecture.batch_id),
             iso(lecture.start), DEPLOYMENT_ZONE,
             iso(lecture.end), DEPLOYMENT_ZONE, lecture.notes],
        ))

    for mark in FIXTURE.marks:
        out.append((
            "INSERT INTO attendance (lecture_id, student_id, present) VALUES (?, ?, ?)",
            [str(mark.lecture_id), str(mark.student_id),
             "true" if mark.present else "false"],
        ))

    return out


# ── Talking to PostgreSQL ────────────────────────────────────────────────


class Postgres:
    """
    A throwaway PostgreSQL container with both migrations applied.

    Reached through psql rather than a driver, so the differ needs nothing installed that
    tools/verify-schema.sh does not already need. Arguments are passed as psql variables
    and interpolated as quoted literals by psql itself, which keeps the values out of the
    statement text and out of reach of any quoting mistake of mine. A cast is doubled in
    the SQL — ``?::date`` — and psql leaves ``::`` alone, which is what makes this work.
    """

    def __init__(self, image: str, keep: bool) -> None:
        self.image = image
        self.keep = keep

    def start(self) -> None:
        subprocess.run(["docker", "rm", "-f", CONTAINER],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        subprocess.run(
            ["docker", "run", "-d", "--name", CONTAINER,
             "-e", "POSTGRES_PASSWORD=differ",
             "-e", "POSTGRES_DB=sies",
             "-e", "TZ=UTC",
             self.image],
            stdout=subprocess.DEVNULL, check=True)

        # pg_isready answers "ready" on the image's temporary initialisation server,
        # before POSTGRES_DB has been created, so the real database is polled instead.
        for _ in range(60):
            probe = subprocess.run(
                ["docker", "exec", CONTAINER,
                 "psql", "-U", "postgres", "-d", "sies", "-tAc", "SELECT 1"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            if probe.returncode == 0:
                break
            time.sleep(1)
        else:
            logs = subprocess.run(["docker", "logs", CONTAINER],
                                  capture_output=True, text=True, check=False)
            raise SystemExit("PostgreSQL never came up:\n" + logs.stderr + logs.stdout)

    def stop(self) -> None:
        if self.keep:
            print(f"\nkept container {CONTAINER}; "
                  f"docker rm -f {CONTAINER} when finished")
            return
        subprocess.run(["docker", "rm", "-f", CONTAINER],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)

    def migrate(self) -> None:
        # By version number, not by name. Sorted as text, V10 comes before V2 and the
        # tenth migration runs first, which fails on a type V7 creates -- the same trap
        # that caught verify-schema.sh, dev-database.sh and TestPostgres when V10 was
        # written, and this is the fourth place it lived.
        def version(name: str) -> int:
            return int(name[1:].split("__", 1)[0])

        names: list[str] = [name for name in os.listdir(MIGRATIONS)
                            if name.startswith("V") and name.endswith(".sql")]

        for name in sorted(names, key=version):
            with open(os.path.join(MIGRATIONS, name), encoding="utf-8") as handle:
                self._psql(handle.read(), [], quiet=True)

    def execute(self, sql: str, args: list[str | None]) -> None:
        self._psql(*self._bind(sql, args))

    def query(self, sql: str, args: list[str | None]) -> list[dict[str, str]]:
        """
        One statement's rows, in order, as text.

        Wrapped in COPY so that the output is CSV with a header and an unambiguous NULL,
        rather than psql's aligned table. COPY preserves the statement's own ORDER BY,
        which is the thing being compared.
        """
        bound_sql, variables = self._bind(sql, args)
        copy = (f"COPY (\n{bound_sql}\n) TO STDOUT "
                f"WITH (FORMAT csv, HEADER true, NULL '{NULL}')")
        output = self._psql(copy, variables)
        rows = list(csv.DictReader(io.StringIO(output)))
        return [dict(row) for row in rows]

    @staticmethod
    def _bind(sql: str, args: list[str | None]) -> tuple[str, list[str]]:
        """
        Replaces each ``?`` with a psql variable reference, in order.

        A null becomes an unquoted reference whose value is the word NULL, because a
        quoted empty string is not a null and the distinction decides whether an absent
        date bound narrows the window or not.
        """
        out: list[str] = []
        variables: list[str] = []
        index = 0

        for character in sql:
            if character != "?":
                out.append(character)
                continue
            if index >= len(args):
                raise AssertionError("more placeholders than arguments")
            name = f"p{index}"
            value = args[index]
            if value is None:
                out.append(f":{name}")
                variables.append(f"{name}=NULL")
            else:
                out.append(f":'{name}'")
                variables.append(f"{name}={value}")
            index += 1

        if index != len(args):
            raise AssertionError(
                f"{len(args)} arguments for {index} placeholders")
        return "".join(out), variables

    def _psql(self, sql: str, variables: list[str], quiet: bool = False) -> str:
        command = ["docker", "exec", "-i", CONTAINER,
                   "psql", "-U", "postgres", "-d", "sies",
                   "-v", "ON_ERROR_STOP=1", "--no-psqlrc"]
        if quiet:
            command.append("-q")
        for variable in variables:
            command += ["-v", variable]
        command += ["-f", "-"]

        result = subprocess.run(command, input=sql, capture_output=True, text=True,
                                check=False)
        if result.returncode != 0:
            raise RuntimeError(
                "psql failed:\n" + result.stderr.strip() + "\n\n" + sql[:2000])
        return result.stdout


# ── Reading the dumps ────────────────────────────────────────────────────


def read_dump(path: str) -> tuple[str, list[str | None]]:
    """
    One dumped statement and its arguments.

    The format is written by SqlBuilderTest and SqlDumpTest alike: the statement, a
    ----ARGS---- line, then one argument per line, where a leading space followed by NULL
    is a null and anything else is the value verbatim. The leading space is the whole
    sentinel: a query is free to bind the four-letter string NULL.
    """
    with open(path, encoding="utf-8") as handle:
        text = handle.read()

    sql, _, rest = text.partition("\n----ARGS----\n")
    args: list[str | None] = []
    for line in rest.split("\n"):
        if line == "":
            continue
        args.append(None if line == " NULL" else line)
    return sql, args


def run_sqlite(conn: sqlite3.Connection, sql: str,
               args: list[str | None]) -> list[dict[str, str]]:
    """
    One statement's rows, in order, with every value turned into text.

    SQLite is typeless enough that an argument's Python type decides how it compares, so
    anything that looks like an integer is bound as one — the app binds longs for ids and
    epochs — and everything else as text. That is what sql_harness does too.
    """
    bound: list[object] = []
    for arg in args:
        if arg is None:
            bound.append(None)
            continue
        try:
            bound.append(int(arg))
        except ValueError:
            bound.append(arg)

    cursor = conn.execute(sql, bound)
    columns = [description[0] for description in cursor.description]
    return [{column: as_text(value) for column, value in zip(columns, row)}
            for row in cursor.fetchall()]


def as_text(value: object) -> str:
    """
    One value as the text it is compared as.

    PostgreSQL hands everything back through CSV, so the SQLite side is flattened the same
    way rather than guessing at types on either side. A float that is a whole number is
    written without its fractional part, because SQLite returns 3.0 where PostgreSQL's
    numeric returns 3 and they are the same answer.
    """
    if value is None:
        return NULL
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


# ── The comparison ───────────────────────────────────────────────────────


def compare(name: str, sqlite_rows: list[dict[str, str]],
            postgres_rows: list[dict[str, str]]) -> None:
    """
    The whole gate: same columns, same number of rows, same values in the same order.

    Order is part of it. Every statistics statement carries an ORDER BY, and a report that
    came back correct but reshuffled would still be a different report.
    """
    sqlite_columns = list(sqlite_rows[0]) if sqlite_rows else []
    postgres_columns = list(postgres_rows[0]) if postgres_rows else []

    if sqlite_rows and postgres_rows and sqlite_columns != postgres_columns:
        check(f"{name}: same columns", False,
              f"sqlite {sqlite_columns}\n      postgres {postgres_columns}")
        return

    if len(sqlite_rows) != len(postgres_rows):
        check(f"{name}: same number of rows", False,
              f"sqlite {len(sqlite_rows)}, postgres {len(postgres_rows)}\n"
              f"      sqlite   {sqlite_rows[:4]}\n"
              f"      postgres {postgres_rows[:4]}")
        return

    for index, (left, right) in enumerate(zip(sqlite_rows, postgres_rows)):
        if left != right:
            differing = {key: (left[key], right.get(key))
                         for key in left if left[key] != right.get(key)}
            check(f"{name}: row {index} agrees", False,
                  f"{differing}\n      sqlite   {left}\n      postgres {right}")
            return

    check(f"{name}: {len(sqlite_rows)} rows agree on both engines", True)


def check_every_statement(conn: sqlite3.Connection, postgres: Postgres,
                          only: str | None) -> None:

    names = sorted(
        os.path.basename(path)[:-4]
        for path in os.listdir(SQLITE_DUMPS)
        if path.endswith(".sql") and not path.startswith("probe_")
    )

    postgres_names = {
        os.path.basename(path)[:-4]
        for path in os.listdir(POSTGRES_DUMPS) if path.endswith(".sql")
    }

    # A case renamed on one side only would otherwise be silently skipped, which is the
    # one failure mode a differ must not have.
    check("every SQLite case has a PostgreSQL counterpart",
          set(names) == postgres_names,
          f"only in app: {sorted(set(names) - postgres_names)}; "
          f"only in server: {sorted(postgres_names - set(names))}")

    for name in names:
        if only is not None and name != only:
            continue
        if name not in postgres_names:
            continue

        sqlite_sql, sqlite_args = read_dump(
            os.path.join(SQLITE_DUMPS, name + ".sql"))
        postgres_sql, postgres_args = read_dump(
            os.path.join(POSTGRES_DUMPS, name + ".sql"))

        try:
            sqlite_rows = run_sqlite(conn, sqlite_sql, sqlite_args)
        except Exception as error:  # noqa: BLE001
            check(f"{name}: runs on SQLite", False,
                  f"{type(error).__name__}: {error}")
            continue

        try:
            postgres_rows = postgres.query(postgres_sql, postgres_args)
        except Exception as error:  # noqa: BLE001
            check(f"{name}: runs on PostgreSQL", False,
                  f"{type(error).__name__}: {error}")
            continue

        compare(name, sqlite_rows, postgres_rows)


# ── The one difference that is deliberate ────────────────────────────────


def check_the_zone_difference_is_real(postgres: Postgres) -> None:
    """
    Demonstrates what the retained zone column buys, by asking the same question twice.

    Lecture 12 starts at 01:00 in Asia/Kolkata, which is 19:30 the previous day in UTC. The
    app's month grouping calls strftime(..., 'localtime'), so its answer depends on the
    zone of whoever runs the report: in Asia/Kolkata the lecture is in October, in UTC it is
    in September. PostgreSQL reads the zone the lecture was recorded in and answers October
    either way.

    This is the only place the differ expects the two engines to disagree, and it is why
    every other comparison is run with the process zone set to the deployment zone.
    """
    sql, args = read_dump(os.path.join(SQLITE_DUMPS, "stats_grouped_MONTH.sql"))

    def months_under(zone: str) -> list[str]:
        previous = os.environ.get("TZ")
        os.environ["TZ"] = zone
        time.tzset()
        try:
            conn = build_sqlite()
            rows = run_sqlite(conn, sql, args)
            conn.close()
        finally:
            if previous is None:
                del os.environ["TZ"]
            else:
                os.environ["TZ"] = previous
            time.tzset()
        return [row["primaryLabel"] for row in rows]

    in_zone = months_under(DEPLOYMENT_ZONE)
    in_utc = months_under("UTC")

    check("the app's month grouping changes with the reader's time zone, which is the "
          "bug the retained zone column removes",
          in_zone != in_utc,
          f"Asia/Kolkata {in_zone}\n      UTC          {in_utc}")

    check("read in the deployment zone, the lecture just after midnight is in October",
          "2025-10" in in_zone, f"{in_zone}")
    check("read in UTC, the same lecture moves to September",
          "2025-09" in in_utc and "2025-10" not in in_utc, f"{in_utc}")

    pg_sql, pg_args = read_dump(os.path.join(POSTGRES_DUMPS, "stats_grouped_MONTH.sql"))
    pg_months = [row["primaryLabel"] for row in postgres.query(pg_sql, pg_args)]

    check("PostgreSQL answers October regardless, because the lecture carries its own "
          "zone and the server runs in UTC",
          "2025-10" in pg_months and "2025-09" not in pg_months, f"{pg_months}")


def check_the_week_rule(conn: sqlite3.Connection, postgres: Postgres) -> None:
    """
    The one translation with no equivalent function, called out on its own.

    It is already covered by stats_grouped_WEEK, but a failure there would read as "row 3
    disagrees" and leave the reason to be worked out. This states what is being checked.
    """
    sqlite_sql, sqlite_args = read_dump(os.path.join(SQLITE_DUMPS, "stats_grouped_WEEK.sql"))
    pg_sql, pg_args = read_dump(os.path.join(POSTGRES_DUMPS, "stats_grouped_WEEK.sql"))

    sqlite_weeks = [row["primaryLabel"] for row in run_sqlite(conn, sqlite_sql, sqlite_args)]
    pg_weeks = [row["primaryLabel"] for row in postgres.query(pg_sql, pg_args)]

    check("the week number matches strftime('%W') across the turn of the year, where "
          "PostgreSQL's own ISO week would not",
          sqlite_weeks == pg_weeks,
          f"sqlite   {sqlite_weeks}\n      postgres {pg_weeks}")

    # The boundaries the fixture was built to probe, named so a failure says which.
    for expected in ("2025-W52", "2026-W00", "2026-W01"):
        check(f"both engines place a lecture in {expected}",
              expected in sqlite_weeks and expected in pg_weeks,
              f"sqlite {sqlite_weeks}, postgres {pg_weeks}")


# ── Entry point ──────────────────────────────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Compares the app's SQLite statements with the server's PostgreSQL "
                    "statements, row for row, over one fixture.")
    parser.add_argument("--pg-image", default=os.environ.get("PG_IMAGE", DEFAULT_IMAGE),
                        help=f"PostgreSQL image to test against (default {DEFAULT_IMAGE})")
    parser.add_argument("--only", default=None,
                        help="compare a single case by name")
    parser.add_argument("--keep", action="store_true",
                        help="leave the container running afterwards")
    arguments = parser.parse_args()

    for directory, how in ((SQLITE_DUMPS, "./gradlew :app:testDebugUnitTest"),
                           (POSTGRES_DUMPS, "./gradlew :server:test")):
        if not os.path.isdir(directory):
            raise SystemExit(f"no dumps in {directory}\nrun {how} first")

    conn = build_sqlite()

    postgres = Postgres(arguments.pg_image, arguments.keep)
    postgres.start()
    try:
        postgres.migrate()
        for sql, args in postgres_inserts():
            postgres.execute(sql, args)

        check_every_statement(conn, postgres, arguments.only)
        if arguments.only is None:
            check_the_week_rule(conn, postgres)
            check_the_zone_difference_is_real(postgres)
    finally:
        postgres.stop()
        conn.close()

    print(f"\npassed {len(passes)}, failed {len(failures)}\n")
    for name, detail in failures:
        print(f"FAIL  {name}")
        if detail:
            print(f"      {detail}")

    if not failures:
        print("Every statement returns identical rows on SQLite and PostgreSQL.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
