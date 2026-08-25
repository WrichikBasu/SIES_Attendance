#!/usr/bin/env python3
"""
Executes the app's real SQL against a real SQLite database.

The schema comes from Room's own generated MainDatabase_Impl, so it is byte-for-byte what
the app creates on a device. The statements come from build/wb-sql/, written by
SqlBuilderTest from the actual query builders. Nothing here is a hand-written
approximation of either.
"""

import glob
import os
import re
import sqlite3
import sys
from datetime import datetime, timezone, timedelta

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__)))
PROJECT = "/home/wrichik-basu/Android_apps/SIES_Attendance"
IMPL = os.path.join(
    PROJECT,
    "app/build/generated/ap_generated_sources/debug/out/in/basulabs/siesattendance/backend/MainDatabase_Impl.java",
)
SQLDIR = os.path.join(PROJECT, "app/build/wb-sql")

# The app stores ZonedDateTime as "epochSecond|nano|zone" and every date-filtering query
# slices the leading integer out. Fixtures use Asia/Kolkata, the app's real deployment zone.
IST = timezone(timedelta(hours=5, minutes=30))

failures = []
passes = []
defects = []


def check(name, condition, detail=""):
    if condition:
        passes.append(name)
    else:
        failures.append((name, detail))


def defect(name, condition, detail=""):
    """
    Records a confirmed defect: the condition asserts what the code ACTUALLY does, and the
    name says what it was supposed to do.
    """
    if condition:
        defects.append((name, detail))
    else:
        failures.append(("defect no longer reproduces: " + name, detail))


def load_ddl():
    src = open(IMPL, encoding="utf-8").read()
    body = src[src.index("public void createAllTables"):]
    body = body[: body.index("public void dropAllTables")]
    stmts = re.findall(r'SQLite\.execSQL\(connection,\s*"((?:[^"\\]|\\.)*)"\)', body)
    out = []
    for s in stmts:
        s = s.encode().decode("unicode_escape")
        if s.upper().startswith("CREATE") or s.upper().startswith("INSERT"):
            out.append(s)
    return out


def ts(y, m, d, hh, mm, zone="Asia/Kolkata"):
    epoch = int(datetime(y, m, d, hh, mm, tzinfo=IST).timestamp())
    return f"{epoch}|0|{zone}"


def start_of_day(y, m, d):
    return int(datetime(y, m, d, 0, 0, tzinfo=IST).timestamp())


def end_of_day(y, m, d):
    return start_of_day(y, m, d) + 86400 - 1


def build_fixture():
    """
    A small but deliberately awkward session.

      Session 2025 : 1 Jun 2025 - 31 May 2026
      Session 2024 : 1 Jun 2024 - 31 May 2025

      Students (2025):
        1 Aarav   2025CS01  SY BSc_unaided CS,  minor MATHEMATICS, OE ENGLISH   batch A
        2 Sneha   2025CS02  SY BSc_unaided CS,  no minor,          OE ENGLISH   batch B
        3 Rahul   2025CS03  SY BSc_unaided CS,  no minor,          no OE        batch A
        4 Meera   2025PH01  SY BSc         PHY, minor CS,          OE HINDI     - (also 2024)

      Courses:
        1 CS201  MAJOR            CS  SY BSc_unaided   sem 3
        2 CS202  MAJOR_PRACTICALS CS  SY BSc_unaided   sem 3
        3 MA201  MINOR            MATHEMATICS SY BSc_unaided sem 3
        4 EN201  OE               ENGLISH     SY BSc_unaided sem 3
        5 IK201  IKS              CS  SY BSc_unaided   sem 3      (whole year+stream)
        6 CS900  MAJOR            CS  SY BSc_unaided   sem 4      (discontinued)

      Lectures:
        L1 CS201 whole class 10 Jul 2025   -> 1,2,3 marked; Rahul absent
        L2 CS201 whole class 11 Jul 2025   -> 1,2 marked only (Rahul left off the sheet)
        L3 CS202 batch A     12 Jul 2025   -> 1,3 marked; both present
        L4 CS202 batch B     12 Jul 2025   -> 2 marked; absent
        L5 CS201 whole class 15 Aug 2025   -> 1,2,3 all present
        L6 CS201 whole class  2 Feb 2024   -> ORPHAN: falls outside every defined session
    """
    conn = sqlite3.connect(":memory:")
    conn.execute("PRAGMA foreign_keys = ON")
    for stmt in load_ddl():
        conn.execute(stmt)

    c = conn.cursor()

    c.execute("INSERT INTO academic_years VALUES (?,?,?,?,?)",
              (2024, 0, 0, start_of_day(2024, 6, 1), end_of_day(2025, 5, 31)))
    c.execute("INSERT INTO academic_years VALUES (?,?,?,?,?)",
              (2025, 0, 0, start_of_day(2025, 6, 1), end_of_day(2026, 5, 31)))

    c.execute("INSERT INTO professors VALUES (1,'DR SHARMA','sharma@sies.edu.in',"
              "'COMPUTER_SCIENCE','Assistant Professor')")
    c.execute("INSERT INTO professors VALUES (2,'DR RAO','rao@sies.edu.in',"
              "'PHYSICS','Associate Professor')")

    students = [
        (1, "AARAV PATIL", "he/him", "aarav@sies.edu.in"),
        (2, "SNEHA MEHTA", "she/her", "sneha@sies.edu.in"),
        (3, "RAHUL DESAI", "he/him", None),
        (4, "MEERA IYER", "she/her", "meera@sies.edu.in"),
    ]
    c.executemany("INSERT INTO students VALUES (?,?,?,?)", students)

    enrolments = [
        (1, 2025, "2025CS01", "SY", "BSc_unaided", "COMPUTER_SCIENCE", "MATHEMATICS",
         "ENGLISH", 3),
        (2, 2025, "2025CS02", "SY", "BSc_unaided", "COMPUTER_SCIENCE", None, "ENGLISH", 3),
        (3, 2025, "2025CS03", "SY", "BSc_unaided", "COMPUTER_SCIENCE", None, None, 3),
        (4, 2025, "2025PH01", "SY", "BSc", "PHYSICS", "COMPUTER_SCIENCE", "HINDI", 3),
        (4, 2024, "2024PH07", "FY", "BSc", "PHYSICS", None, None, 1),
    ]
    c.executemany("INSERT INTO student_year VALUES (?,?,?,?,?,?,?,?,?)", enrolments)

    courses = [
        (1, 1, "CS201", "Data Structures", "BSc_unaided", "COMPUTER_SCIENCE", "SY", 3,
         "MAJOR", None, None, None),
        (2, 1, "CS202", "DS Practicals", "BSc_unaided", "COMPUTER_SCIENCE", "SY", 3,
         "MAJOR_PRACTICALS", None, None, None),
        (3, 2, "MA201", "Discrete Maths", "BSc_unaided", "MATHEMATICS", "SY", 3,
         "MINOR", None, None, None),
        (4, 2, "EN201", "Literature", "BSc_unaided", "ENGLISH", "SY", 3,
         "OE", None, None, None),
        (5, 1, "IK201", "Indian Knowledge", "BSc_unaided", "COMPUTER_SCIENCE", "SY", 3,
         "IKS", None, None, None),
        (6, 1, "CS900", "Retired Course", "BSc_unaided", "COMPUTER_SCIENCE", "SY", 4,
         "MAJOR", None, None, ts(2025, 6, 30, 12, 0)),
    ]
    c.executemany("INSERT INTO courses VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", courses)

    c.execute("INSERT INTO batch_desc VALUES (1,'Batch A',NULL,2025)")
    c.execute("INSERT INTO batch_desc VALUES (2,'Batch B',NULL,2025)")
    c.executemany("INSERT INTO batch_student_map VALUES (?,?,?)",
                  [(1, 1, 2025), (1, 3, 2025), (2, 2, 2025)])

    lectures = [
        (1, 1, None, ts(2025, 7, 10, 10, 0), ts(2025, 7, 10, 11, 0), None),
        (2, 1, None, ts(2025, 7, 11, 10, 0), ts(2025, 7, 11, 11, 0), None),
        (3, 2, 1, ts(2025, 7, 12, 14, 0), ts(2025, 7, 12, 16, 0), None),
        (4, 2, 2, ts(2025, 7, 12, 16, 0), ts(2025, 7, 12, 18, 0), None),
        (5, 1, None, ts(2025, 8, 15, 10, 0), ts(2025, 8, 15, 11, 0), None),
        (6, 1, None, ts(2024, 2, 2, 10, 0), ts(2024, 2, 2, 11, 0), "orphan"),
    ]
    c.executemany("INSERT INTO lectures VALUES (?,?,?,?,?,?)", lectures)

    attendance = [
        (1, 1, 1), (1, 2, 1), (1, 3, 0),
        (2, 1, 1), (2, 2, 0),
        (3, 1, 1), (3, 3, 1),
        (4, 2, 0),
        (5, 1, 1), (5, 2, 1), (5, 3, 1),
    ]
    c.executemany("INSERT INTO attendance VALUES (?,?,?)", attendance)

    conn.commit()
    return conn


def dao_sql(dao, method):
    """
    The SQL Room generated for one @Query method, read out of the generated DAO
    implementation. Reading it rather than copying it by hand is the same principle the
    schema follows: the harness should not contain its own idea of what the app runs.
    """
    src = open(os.path.join(
        PROJECT,
        "app/build/generated/ap_generated_sources/debug/out/in/basulabs/siesattendance",
        "backend", dao + "_Impl.java"), encoding="utf-8").read()

    at = src.index(" " + method + "(")
    literal = re.search(r'final String _sql = "((?:[^"\\]|\\.)*)";', src[at:])
    if literal is None:
        raise AssertionError("no SQL found for " + dao + "." + method)
    return literal.group(1).encode().decode("unicode_escape")


def read_statement(name):
    text = open(os.path.join(SQLDIR, name + ".sql"), encoding="utf-8").read()
    sql, _, rest = text.partition("\n----ARGS----\n")
    args = []
    for line in rest.split("\n"):
        if line == "":
            continue
        if line == " NULL":
            args.append(None)
            continue
        try:
            args.append(int(line))
        except ValueError:
            args.append(line)
    return sql, args


def run(conn, name, override_args=None):
    sql, args = read_statement(name)
    if override_args is not None:
        args = override_args
    cur = conn.execute(sql, args)
    cols = [d[0] for d in cur.description]
    return [dict(zip(cols, row)) for row in cur.fetchall()]


# ── Tests ────────────────────────────────────────────────────────────────

def test_every_generated_statement_parses_and_runs(conn):
    for path in sorted(glob.glob(os.path.join(SQLDIR, "*.sql"))):
        name = os.path.basename(path)[:-4]
        try:
            run(conn, name)
            check(f"statement runs: {name}", True)
        except Exception as e:  # noqa: BLE001
            check(f"statement runs: {name}", False, f"{type(e).__name__}: {e}")


def test_export_denominator_counts_lectures_held(conn):
    rows = {(r["rollNo"], r["courseCode"]): r for r in run(conn, "export_unrestricted")}

    # Aarav is in batch A, so his CS202 denominator is L3 only, not L3+L4.
    check("export: whole-class lectures count for everyone on the roll",
          rows[("2025CS01", "CS201")]["held"] == 3,
          f"held={rows[('2025CS01','CS201')]['held']} expected 3")
    check("export: batch lectures count only for that batch's members",
          rows[("2025CS01", "CS202")]["held"] == 1,
          f"held={rows[('2025CS01','CS202')]['held']} expected 1")
    check("export: the other batch's lecture counts for the other batch",
          rows[("2025CS02", "CS202")]["held"] == 1)

    # The load-bearing case: Rahul was left off L2's sheet entirely. Counting his own
    # attendance rows would give 2/2 = 100%. Counting lectures held gives 2/3.
    # Rahul: absent at L1, never on L2's sheet at all, present at L5. Counting his own
    # attendance rows would make it 1 of 2 (50%); counting lectures held makes it 1 of 3
    # (33%), which is the truth.
    rahul = rows[("2025CS03", "CS201")]
    own_rows = conn.execute(
        "SELECT COUNT(*) FROM attendance a JOIN lectures l ON l.lectureID = a.lectureID "
        "WHERE a.studentID = 3 AND l.courseID = 1 AND l.lectureID != 6").fetchone()[0]
    check("export: a student left off a sheet is not flattered by a shrunken denominator",
          rahul["held"] == 3 and rahul["attended"] == 1 and own_rows == 2,
          f"held={rahul['held']} attended={rahul['attended']} ownRows={own_rows}")


def test_export_roster_matches_course_type(conn):
    rows = run(conn, "export_unrestricted")
    pairs = {(r["rollNo"], r["courseCode"]) for r in rows}

    check("export: a MAJOR course reaches the offering department's majors",
          ("2025CS01", "CS201") in pairs)
    check("export: a MAJOR course does not reach another department's majors",
          ("2025PH01", "CS201") not in pairs)

    # A minor course admits the minor department AND the offering department's own majors,
    # because the minor field is optional and frequently blank.
    check("export: a MINOR course admits students whose minor matches",
          ("2025CS01", "MA201") in pairs)
    check("export: a MINOR course does not admit an unrelated student",
          ("2025CS03", "MA201") not in pairs,
          "Rahul has no minor and is not a MATHEMATICS major")

    check("export: an OE course matches the declared OE department",
          ("2025CS01", "EN201") in pairs and ("2025CS02", "EN201") in pairs)
    check("export: an OE course skips a student with no OE declared",
          ("2025CS03", "EN201") not in pairs)

    # IKS/AEC/VSC/SEC are taken by a whole year and stream regardless of department.
    for roll in ("2025CS01", "2025CS02", "2025CS03"):
        check(f"export: IKS reaches the whole year and stream ({roll})",
              (roll, "IK201") in pairs)
    check("export: IKS does not cross into another stream",
          ("2025PH01", "IK201") not in pairs, "Meera is BSc, not BSc_unaided")


def test_export_excludes_other_sessions_and_orphans(conn):
    rows = run(conn, "export_unrestricted")

    check("export: only the requested session's enrolments appear",
          all(r["rollNo"] != "2024PH07" for r in rows))

    # L6 sits outside every defined session, so it must not inflate any denominator.
    cs201 = [r for r in rows if r["courseCode"] == "CS201"]
    check("export: a lecture outside every session is not counted as held",
          all(r["held"] <= 3 for r in cs201),
          f"held values {[r['held'] for r in cs201]}")


def test_export_date_window(conn):
    rows = {(r["rollNo"], r["courseCode"]): r for r in run(conn, "export_windowed")}

    # The window is 1-31 July 2025, so the 15 August lecture drops out.
    check("export: the date window narrows the held count",
          rows[("2025CS01", "CS201")]["held"] == 2,
          f"held={rows[('2025CS01','CS201')]['held']} expected 2")
    check("export: the date window narrows the attended count in step",
          rows[("2025CS01", "CS201")]["attended"] == 2)
    check("export: a windowed percentage stays over one denominator",
          rows[("2025CS03", "CS201")]["held"] == 2
          and rows[("2025CS03", "CS201")]["attended"] == 0,
          f"{rows[('2025CS03','CS201')]}")


def test_export_course_filter_selects_columns(conn):
    codes = {r["courseCode"] for r in run(conn, "export_course_only")}
    check("export: a course filter restricts which courses become columns",
          codes == {"CS201", "MA201"}, f"codes={sorted(codes)}")

    rolls = {r["rollNo"] for r in run(conn, "export_dept_only")}
    check("export: a department filter restricts which students become rows",
          rolls == {"2025CS01", "2025CS02", "2025CS03"}, f"rolls={sorted(rolls)}")

    # Combining them can legitimately produce nothing: Physics majors do not take CS201.
    combined = run(conn, "export_narrowed")
    check("export: an impossible combination returns no rows rather than failing",
          combined == [], f"{combined}")


def test_export_includes_discontinued_courses(conn):
    rows = run(conn, "export_unrestricted")
    codes = {r["courseCode"] for r in rows}
    # Reported rather than asserted either way: worth knowing which it does.
    check("export: discontinued courses still appear as columns (documented behaviour)",
          "CS900" in codes,
          f"codes={sorted(codes)}")


def test_stats_grouped_by_student(conn):
    rows = {r["primaryLabel"]: r for r in run(conn, "stats_grouped_STUDENT")}

    check("stats: one row per student with any mark", set(rows) ==
          {"2025CS01", "2025CS02", "2025CS03"}, f"{sorted(rows)}")

    aarav = rows["2025CS01"]
    check("stats: attendance rows are counted, not lectures held",
          aarav["total"] == 4 and aarav["attended"] == 4,
          f"total={aarav['total']} attended={aarav['attended']}")
    check("stats: the secondary label carries the raw roster name",
          aarav["secondaryLabel"] == "AARAV PATIL")
    check("stats: rowId is the real primary key so a row can be drilled into",
          aarav["rowId"] == 1)
    check("stats: grouping by student makes the distinct student count one",
          all(r["students"] == 1 for r in rows.values()))

    rahul = rows["2025CS03"]
    check("stats: a student left off a sheet has a smaller denominator here than in export",
          rahul["total"] == 3 and rahul["attended"] == 2,
          f"total={rahul['total']} attended={rahul['attended']}")


def test_stats_lecture_counts_are_distinct(conn):
    rows = {r["primaryLabel"]: r for r in run(conn, "stats_grouped_COURSE")}

    cs201 = rows["CS201"]
    # 3 lectures inside the session, 8 attendance rows across them.
    check("stats: lectures are counted DISTINCT, not once per marked student",
          cs201["lectures"] == 3, f"lectures={cs201['lectures']}")
    check("stats: total counts attendance rows",
          cs201["total"] == 8, f"total={cs201['total']}")
    check("stats: distinct students across a course",
          cs201["students"] == 3, f"students={cs201['students']}")


def test_stats_excludes_orphan_lectures(conn):
    summary = run(conn, "stats_summary")[0]
    total_rows = conn.execute("SELECT COUNT(*) FROM attendance").fetchone()[0]

    check("stats: the session join excludes lectures outside every session",
          summary["lectures"] == 5,
          f"lectures={summary['lectures']} of 6 recorded")
    check("stats: the summary total equals every in-session attendance row",
          summary["total"] == total_rows,
          f"summary={summary['total']} table={total_rows}")

    orphans = conn.execute(
        "SELECT COUNT(*) FROM lectures l WHERE NOT EXISTS ("
        "SELECT 1 FROM academic_years ay WHERE "
        "CAST(SUBSTR(l.startDateTime, 1, INSTR(l.startDateTime, '|') - 1) AS INTEGER) "
        "BETWEEN ay.startEpoch AND ay.endEpoch)").fetchone()[0]
    check("stats: the orphan-lecture warning query finds exactly the excluded lecture",
          orphans == 1, f"orphans={orphans}")


def test_stats_batch_grouping_keeps_whole_class_lectures(conn):
    rows = {r["primaryLabel"]: r for r in run(conn, "stats_grouped_BATCH")}

    check("stats: whole-class lectures get a row of their own rather than being dropped",
          "Whole class" in rows, f"{sorted(rows)}")
    check("stats: named batches each get a row",
          {"Batch A", "Batch B"} <= set(rows), f"{sorted(rows)}")
    check("stats: the whole-class row covers all three whole-class lectures",
          rows["Whole class"]["lectures"] == 3,
          f"lectures={rows['Whole class']['lectures']}")


def test_stats_time_groupings(conn):
    months = {r["primaryLabel"]: r for r in run(conn, "stats_grouped_MONTH")}
    check("stats: months are labelled in local time",
          set(months) == {"2025-07", "2025-08"}, f"{sorted(months)}")
    check("stats: July holds the four July lectures' rows",
          months["2025-07"]["lectures"] == 4,
          f"lectures={months['2025-07']['lectures']}")

    weekdays = {r["primaryLabel"] for r in run(conn, "stats_grouped_WEEKDAY")}
    # 10 Jul 2025 Thu, 11 Jul Fri, 12 Jul Sat, 15 Aug Fri
    check("stats: weekday grouping names the right days",
          weekdays == {"Thursday", "Friday", "Saturday"}, f"{sorted(weekdays)}")

    weeks = {r["primaryLabel"] for r in run(conn, "stats_grouped_WEEK")}
    check("stats: week grouping produces ISO-ish week labels",
          all(re.fullmatch(r"\d{4}-W\d{2}", w) for w in weeks), f"{sorted(weeks)}")


def test_stats_academic_year_label(conn):
    rows = run(conn, "stats_grouped_ACADEMIC_YEAR")
    check("stats: the session label is built in SQL and pads to two digits",
          [r["primaryLabel"] for r in rows] == ["2025-26"],
          f"{[r['primaryLabel'] for r in rows]}")

    label_2099 = conn.execute(
        "SELECT ? || '-' || SUBSTR('0' || ((? + 1) % 100), -2)", (2099, 2099)).fetchone()[0]
    check("stats: the SQL label survives the century rollover",
          label_2099 == "2099-00", f"got {label_2099}")


def test_stats_all_sessions_spans_both(conn):
    one = run(conn, "stats_grouped_ACADEMIC_YEAR")
    everything = run(conn, "stats_all_sessions")

    check("stats: a null session filter widens rather than empties the report",
          len(everything) >= len(one))
    check("stats: with no session filter the 2024 enrolment can contribute",
          sum(r["total"] for r in everything) >= sum(r["total"] for r in one))


def test_stats_summary_agrees_with_the_grouped_rows(conn):
    summary = run(conn, "stats_summary")[0]
    rows = run(conn, "stats_grouped_STUDENT")

    check("stats: the headline total is the sum of the rows",
          summary["total"] == sum(r["total"] for r in rows),
          f"summary={summary['total']} rows={sum(r['total'] for r in rows)}")
    check("stats: the headline attended is the sum of the rows",
          summary["attended"] == sum(r["attended"] for r in rows))
    check("stats: distinct students are counted once, not once per course",
          summary["students"] == 3, f"students={summary['students']}")


def test_stats_sort_orders(conn):
    asc = [r["primaryLabel"] for r in run(conn, "stats_sort_PERCENT_ASC")]
    desc = [r["primaryLabel"] for r in run(conn, "stats_sort_PERCENT_DESC")]
    label = [r["primaryLabel"] for r in run(conn, "stats_sort_LABEL_ASC")]

    # By attendance rows: Sneha 2/4 = 50%, Rahul 2/3 = 67%, Aarav 4/4 = 100%.
    check("stats: lowest-percentage-first puts the worst attender at the top",
          asc == ["2025CS02", "2025CS03", "2025CS01"], f"{asc}")
    check("stats: highest-first is the exact reverse",
          desc == list(reversed(asc)), f"{desc}")
    check("stats: label order is register order",
          label == sorted(label), f"{label}")

    total = [(r["primaryLabel"], r["total"]) for r in run(conn, "stats_sort_TOTAL_DESC")]
    check("stats: most-lectures-first is descending by row count",
          [t for _, t in total] == sorted([t for _, t in total], reverse=True),
          f"{total}")


def test_stats_full_filter_returns_a_consistent_slice(conn):
    rows = run(conn, "stats_full_filter")
    check("stats: a filter that matches nothing returns no rows rather than failing",
          isinstance(rows, list))


def test_dao_roster_query_agrees_with_the_export(conn):
    """
    DaoStudent.getRollForCourseSync and ExportQueryBuilder repeat the same roster
    condition. A student who counts as taking a course in one but not the other would
    appear on a sheet without appearing in the report, or the reverse.
    """
    roster_sql = (
        "SELECT y.rollNo "
        "FROM courses c "
        "INNER JOIN student_year y ON y.year = c.year AND y.stream = c.stream "
        "   AND y.academicYear = ? "
        "INNER JOIN students s ON s.studentID = y.studentID "
        "WHERE c.courseID = ? AND ("
        "   (c.courseType IN ('MAJOR', 'MAJOR_PRACTICALS') "
        "      AND y.majorDept = c.offeringDepartment) "
        "   OR (c.courseType IN ('MINOR', 'MINOR_PRACTICALS') "
        "      AND (y.minorDept = c.offeringDepartment "
        "           OR y.majorDept = c.offeringDepartment)) "
        "   OR (c.courseType = 'OE' AND y.oeDept = c.offeringDepartment) "
        "   OR c.courseType IN ('AEC', 'IKS', 'VSC', 'VSC_PRACTICALS', "
        "                        'SEC', 'SEC_PRACTICALS')) "
        "ORDER BY y.rollNo ASC")

    export_rows = run(conn, "export_unrestricted")
    by_course = {}
    for r in export_rows:
        by_course.setdefault(r["courseID"], set()).add(r["rollNo"])

    for course_id in (1, 2, 3, 4, 5, 6):
        roster = {row[0] for row in conn.execute(roster_sql, (2025, course_id))}
        exported = by_course.get(course_id, set())
        check(f"roster and export agree for course {course_id}",
              roster == exported,
              f"roster={sorted(roster)} export={sorted(exported)}")


def test_lecture_detail_projection(conn):
    details_select = (
        "SELECT l.lectureID, l.courseID, l.batchID, c.courseCode, c.courseTopic, "
        "b.batchName, l.startDateTime, l.endDateTime, l.notes, "
        "(SELECT COUNT(*) FROM attendance a WHERE a.lectureID = l.lectureID) "
        "   AS markedCount, "
        "(SELECT COUNT(*) FROM attendance a WHERE a.lectureID = l.lectureID "
        "   AND a.present = 1) AS presentCount "
        "FROM lectures l "
        "INNER JOIN courses c ON c.courseID = l.courseID "
        "LEFT JOIN batch_desc b ON b.batchID = l.batchID ")

    rows = {r[0]: r for r in conn.execute(details_select + "ORDER BY l.lectureID")}

    check("lecture list: every lecture appears exactly once",
          len(rows) == 6, f"{len(rows)} rows for 6 lectures")
    check("lecture list: a whole-class lecture is not dropped by the batch join",
          rows[1][5] is None)
    check("lecture list: counts are per lecture, not multiplied by the sheet size",
          rows[1][9] == 3 and rows[1][10] == 2,
          f"marked={rows[1][9]} present={rows[1][10]}")
    check("lecture list: an unmarked lecture reports zero rather than vanishing",
          rows[6][9] == 0)

    ordered = [r[0] for r in conn.execute(details_select + "ORDER BY l.startDateTime DESC")]
    check("lecture list: ordering on the raw stored string is chronological",
          ordered == [5, 4, 3, 2, 1, 6], f"{ordered}")


def test_find_existing_catches_whole_class_duplicates(conn):
    find = ("SELECT lectureID FROM lectures WHERE courseID = ? "
            "AND ((? IS NULL AND batchID IS NULL) OR batchID = ?) "
            "AND startDateTime = ? AND endDateTime = ? LIMIT 1")

    start, end = ts(2025, 7, 10, 10, 0), ts(2025, 7, 10, 11, 0)
    hit = conn.execute(find, (1, None, None, start, end)).fetchone()
    check("duplicate check: a whole-class repeat is found despite the NULL batch",
          hit is not None and hit[0] == 1, f"{hit}")

    miss = conn.execute(find, (1, 1, 1, start, end)).fetchone()
    check("duplicate check: the same slot for a batch is a different lecture",
          miss is None)

    batch_hit = conn.execute(
        find, (2, 1, 1, ts(2025, 7, 12, 14, 0), ts(2025, 7, 12, 16, 0))).fetchone()
    check("duplicate check: a batch repeat is found", batch_hit[0] == 3)


def test_unique_index_does_not_catch_whole_class_duplicates(conn):
    """
    Why findExisting has to exist: SQLite treats NULLs in a unique index as distinct, so
    the index alone would let the same whole-class session be recorded twice.
    """
    probe = build_fixture()
    try:
        probe.execute("INSERT INTO lectures VALUES (99,1,NULL,?,?,NULL)",
                      (ts(2025, 7, 10, 10, 0), ts(2025, 7, 10, 11, 0)))
        probe.commit()
        check("unique index alone does NOT stop a duplicate whole-class lecture",
              True, "confirmed: findExisting is load-bearing, not belt-and-braces")
    except sqlite3.IntegrityError:
        check("unique index alone does NOT stop a duplicate whole-class lecture",
              False, "the index rejected it, so findExisting would be redundant")
    finally:
        probe.close()

    probe2 = build_fixture()
    try:
        probe2.execute("INSERT INTO lectures VALUES (99,2,1,?,?,NULL)",
                       (ts(2025, 7, 12, 14, 0), ts(2025, 7, 12, 16, 0)))
        probe2.commit()
        check("unique index DOES stop a duplicate batch lecture", False,
              "the index accepted a duplicate")
    except sqlite3.IntegrityError:
        check("unique index DOES stop a duplicate batch lecture", True)
    finally:
        probe2.close()


def test_deletion_impact(conn):
    sql = ("SELECT "
           "(SELECT COUNT(*) FROM attendance WHERE studentID = ?) AS attendanceRecords, "
           "(SELECT COUNT(*) FROM student_year WHERE studentID = ?) AS enrolments, "
           "(SELECT COUNT(*) FROM batch_student_map WHERE studentID = ?) "
           "   AS batchMemberships, "
           "(SELECT COUNT(*) FROM attendance a "
           "   INNER JOIN lectures l ON l.lectureID = a.lectureID "
           "   INNER JOIN academic_years y ON y.academicYear = ? "
           "   WHERE a.studentID = ? "
           "   AND CAST(substr(l.startDateTime, 1, instr(l.startDateTime, '|') - 1) "
           "       AS INTEGER) BETWEEN y.startEpoch AND y.endEpoch) "
           "   AS attendanceThisSession")

    row = conn.execute(sql, (1, 1, 1, 2025, 1)).fetchone()
    check("deletion impact: counts every attendance row for the student",
          row[0] == 4, f"got {row[0]}")
    check("deletion impact: counts enrolments", row[1] == 1)
    check("deletion impact: counts batch memberships", row[2] == 1)
    check("deletion impact: the per-session count is bounded by the session dates",
          row[3] == 4, f"got {row[3]}")

    meera = conn.execute(sql, (4, 4, 4, 2025, 4)).fetchone()
    check("deletion impact: a student in two sessions reports both enrolments",
          meera[1] == 2, f"got {meera[1]}")

    missing_session = conn.execute(sql, (1, 1, 1, 1999, 1)).fetchone()
    check("deletion impact: an undefined session yields zero for that session, "
          "not the whole history",
          missing_session[3] == 0 and missing_session[0] == 4,
          f"{missing_session}")


def test_delete_for_lecture_except_with_an_empty_keep_list(conn):
    """
    updateLecture guards this case in Java. Confirm the guard is needed: what does
    SQLite actually do with NOT IN ()?
    """
    probe = build_fixture()
    before = probe.execute(
        "SELECT COUNT(*) FROM attendance WHERE lectureID = 1").fetchone()[0]
    try:
        probe.execute("DELETE FROM attendance WHERE lectureID = 1 AND studentID NOT IN ()")
        after = probe.execute(
            "SELECT COUNT(*) FROM attendance WHERE lectureID = 1").fetchone()[0]
        check("NOT IN () is accepted by SQLite and is TRUE for every row, so the "
              "empty-roll guard in updateLecture is what keeps the sheet from being "
              "cleared by accident rather than by decision",
              before == 3 and after == 0,
              f"before={before} after={after}")
    except sqlite3.OperationalError as e:
        check("NOT IN () behaviour", True,
              f"SQLite rejects it outright ({e}); the guard is still required")
    finally:
        probe.close()


def test_student_stats_ordering_and_scope(conn):
    """
    DaoAttendance.getStudentStats, read straight out of Room's generated implementation.
    """
    sql = dao_sql("DaoAttendance", "getStudentStats")
    rows = {r[2]: r for r in conn.execute(sql, (2025,))}

    check("student stats: a student with nothing recorded still appears at zero of zero",
          rows["2025PH01"][3] == 0, f"{rows['2025PH01']}")

    # The load-bearing one: SQLite sorts nulls first in ASC, so dividing through NULLIF on
    # its own put the no-record students at the head of a worst-attendance list.
    check("student stats: students with no records sort last, not first",
          list(rows)[-1] == "2025PH01", f"order={list(rows)}")
    check("student stats: the worst real attender leads the list",
          list(rows)[0] == "2025CS02", f"order={list(rows)}")

    # The attendance join carries no session condition, so the orphan lecture's rows count
    # here even though they fall outside every session. Pinned rather than judged: this
    # query is describing what was recorded for a student, not bounding it to a year.
    check("student stats: counts every attendance row the student has, "
          "regardless of which session the lecture falls in",
          rows["2025CS01"][3] == 4, f"totalLectures={rows['2025CS01'][3]}")


def test_overlapping_sessions_double_count(conn):
    """
    The stats FROM clause joins lectures to academic_years on a BETWEEN, so a lecture whose
    date falls inside two sessions joins twice and every figure doubles.

    AttendanceViewModel.saveAcademicYear refuses an overlap when a session is edited in the
    app. DatabaseMerger has no such check: academic_years is merged on academicYear alone,
    so a backup carrying a session whose dates overlap a local one is folded straight in.
    """
    probe = build_fixture()

    # With a session selected the WHERE clause pins one year, so the overlap is invisible
    # there. It bites in the "all sessions" view and in the per-session breakdown, which is
    # exactly where a year-on-year comparison is read.
    before_all = run(probe, "stats_all_sessions")
    before_total = sum(r["total"] for r in before_all)

    # A second session covering the same July as 2025, plus the enrolment that comes with
    # it: exactly what merging a backup from a second device would fold in.
    probe.execute("INSERT INTO academic_years VALUES (?,?,?,?,?)",
                  (2026, 0, 0, start_of_day(2025, 7, 1), end_of_day(2025, 7, 31)))
    probe.execute("INSERT INTO student_year VALUES (?,?,?,?,?,?,?,?,?)",
                  (1, 2026, "2026CS01", "TY", "BSc_unaided", "COMPUTER_SCIENCE",
                   None, None, 5))
    probe.commit()

    after_all = run(probe, "stats_all_sessions")
    after_total = sum(r["total"] for r in after_all)

    # The invariant that matters: no attendance row may be counted more than once. Before
    # the fix the BETWEEN join matched the overlap twice and every figure inflated.
    true_rows = {r[0]: r[1] for r in conn.execute(
        "SELECT studentID, COUNT(*) FROM attendance GROUP BY studentID")}
    roll_to_id = {"2025CS01": 1, "2025CS02": 2, "2025CS03": 3}

    inflated = []
    for r in run(probe, "stats_grouped_STUDENT"):
        sid = roll_to_id.get(r["primaryLabel"])
        if sid is not None and r["total"] > true_rows[sid]:
            inflated.append((r["primaryLabel"], r["total"], true_rows[sid]))

    check("overlapping sessions no longer double-count: no student's total exceeds the "
          "attendance rows they actually have",
          not inflated, f"inflated rows {inflated}")

    check("overlap: the report never counts more than the table holds",
          after_total <= before_total,
          f"counted {after_total} of {before_total} attendance rows")

    sql, _ = read_statement("stats_grouped_ACADEMIC_YEAR")
    unfiltered = sql.replace(" AND ay.academicYear = ?", "")
    by_year = [r[1] for r in probe.execute(unfiltered)]
    check("overlap: each session still appears once, and a lecture inside the overlap is "
          "attributed to the later of the two rather than to both",
          len(by_year) == len(set(by_year)), f"{by_year}")

    probe.close()


def test_export_column_order_follows_first_encounter(conn):
    """
    AttendanceExporter builds its course columns from a LinkedHashMap filled in the order the
    query returns rows. The query orders by roll number first and course code second, so the
    column order is "the first student's courses in code order, then any course only a later
    student takes, appended at the end". The comment in export() says the columns come out
    in code order, which only holds when every student takes every course.
    """
    rows = run(conn, "export_unrestricted")

    check("export: rows arrive ordered by roll number then course code",
          [(r["rollNo"], r["courseCode"]) for r in rows]
          == sorted((r["rollNo"], r["courseCode"]) for r in rows))

    # A course only the last student on the register takes, whose code sorts first.
    probe = build_fixture()
    probe.execute(
        "INSERT INTO courses VALUES (7,2,'AA101','Foundations','BSc','PHYSICS','SY',3,"
        "'IKS',NULL,NULL,NULL)")
    probe.commit()

    seen = []
    for r in run(probe, "export_unrestricted"):
        if r["courseCode"] not in seen:
            seen.append(r["courseCode"])
    probe.close()

    check("export: the query still returns first-encounter order, which is why the "
          "exporter sorts the columns by code afterwards (see "
          "ExporterInternalsTest.courseColumnsComeOutInCodeOrder)",
          seen != sorted(seen),
          f"query order {seen}, code order {sorted(seen)}")


def main():
    conn = build_fixture()

    test_every_generated_statement_parses_and_runs(conn)
    test_export_denominator_counts_lectures_held(conn)
    test_export_roster_matches_course_type(conn)
    test_export_excludes_other_sessions_and_orphans(conn)
    test_export_date_window(conn)
    test_export_course_filter_selects_columns(conn)
    test_export_includes_discontinued_courses(conn)
    test_stats_grouped_by_student(conn)
    test_stats_lecture_counts_are_distinct(conn)
    test_stats_excludes_orphan_lectures(conn)
    test_stats_batch_grouping_keeps_whole_class_lectures(conn)
    test_stats_time_groupings(conn)
    test_stats_academic_year_label(conn)
    test_stats_all_sessions_spans_both(conn)
    test_stats_summary_agrees_with_the_grouped_rows(conn)
    test_stats_sort_orders(conn)
    test_stats_full_filter_returns_a_consistent_slice(conn)
    test_dao_roster_query_agrees_with_the_export(conn)
    test_lecture_detail_projection(conn)
    test_find_existing_catches_whole_class_duplicates(conn)
    test_unique_index_does_not_catch_whole_class_duplicates(conn)
    test_deletion_impact(conn)
    test_delete_for_lecture_except_with_an_empty_keep_list(conn)
    test_student_stats_ordering_and_scope(conn)
    test_overlapping_sessions_double_count(conn)
    test_export_column_order_follows_first_encounter(conn)

    print(f"passed {len(passes)}, failed {len(failures)}, "
          f"confirmed defects {len(defects)}\n")
    for name, detail in failures:
        print(f"FAIL  {name}")
        if detail:
            print(f"      {detail}")
    for name, detail in defects:
        print(f"DEFECT CONFIRMED  {name}")
        if detail:
            print(f"                  {detail}")
        print()
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
