# server

The central PostgreSQL database for SIES Attendance, and in time the Spring Boot service in
front of it. This directory currently holds **Phase 1 only**: the schema.

There is no build file yet. The migrations are plain SQL, laid out where Flyway will expect
them, and applied by `tools/verify-schema.sh` until the Spring Boot module arrives in Phase 2.

```
server/
├── src/main/resources/db/migration/
│   ├── V1__baseline.sql        the Room schema at version 10, translated
│   └── V2__constraints.sql     what SQLite could not express
├── src/test/sql/
│   └── constraint_smoke_test.sql
└── tools/
    └── verify-schema.sh
```

## Verifying it

```sh
./server/tools/verify-schema.sh            # apply, smoke-test, summarise
./server/tools/verify-schema.sh --dump     # also print the resulting DDL
```

Needs `docker` and a local `postgres:18` image (override with `PG_IMAGE`). It starts a
throwaway container, applies both migrations to an empty database, runs the smoke test, and
removes the container. Nothing is left behind and no existing database is touched.

The drift guard runs in the app's ordinary unit test suite, so it needs no database:

```sh
./gradlew :app:testDebugUnitTest --tests '*PostgresSchemaDriftTest*'
```

## The two migrations

**`V1__baseline.sql` is a translation, not a redesign.** Every table, column, index and foreign
key in it exists in the device database today. The source of truth is the DDL Room generates
into `app/build/generated/ap_generated_sources/debug/out/.../MainDatabase_Impl.java`, which is
byte-for-byte what the app creates on a phone. Keeping V1 literal is what makes it reviewable
against that file by eye, and what will make the Phase 3 import verifiable.

It deviates in three ways, each deliberate and each noted in the file:

- **Identifiers are snake_case.** Unquoted identifiers fold to lower case in PostgreSQL, so
  `studentID` would silently become `studentid`, and quoting every identifier forever is worse.
  The column map at the foot of V1 is authoritative for the importer.
- **Enum columns become native enum types**, with the Java `name()` strings as values, in
  declaration order.
- **Three of Room's indexes are omitted** because a primary key already provides them:
  `index_professors_profID`, `index_professors_name_department_email` (implied by the unique
  index on email alone) and `index_batch_desc_batchID_academicYear`.

**`V2__constraints.sql` is purely additive.** It drops and alters nothing from V1. It holds the
invariants `AcademicRules.validate()` enforces in Java today — which hold only on the paths that
remember to call it, and `CsvImporter`, `FragmentAddStudent` and `FragmentAddCourse` each call it
separately — plus the guarantees SQLite had no way to state.

## What changed from the device schema, and why

| Device | Server | Reason |
| --- | --- | --- |
| `ZonedDateTime` as `"epochSecond\|nano\|zone"` text | `timestamptz` + a `_zone` column | Every date filter had to slice the leading integer out (`SqlBits.EPOCH_START`) because the text sorts lexicographically. The zone is kept in its own column: `timestamptz` is an instant and renders in the *reader's* zone, so dropping it would move every lecture by hours for a report read from elsewhere. |
| `academic_years.startEpoch` / `endEpoch` | dropped; replaced by a `daterange` exclusion constraint | They were derived from the dates at the **writing device's** time zone, so two phones in different zones produce different values for the same session and the stored number may be either. Recompute, never copy. |
| `LocalDate` as epoch day | `date` | Unambiguous either way. |
| `present` as `INTEGER` 0/1 | `boolean` | — |
| enum columns as `TEXT` | native enum types | The database now rejects an unknown value at write time rather than `TypeConvertors.valueOf` throwing at read time, possibly a year later. |
| `semester` as nullable `INTEGER`, validated in Java | `smallint` + `CHECK` | NULL stays valid on `student_year`: the column arrived at schema version 6 and older rows have no honest value. |
| nothing | `created_at` / `created_by` / `updated_at` / `updated_by` / `row_version` | All nullable. Rows imported from device history must leave them NULL — see below. |

## Two things worth knowing before Phase 3

**`batch_desc` had no uniqueness on `(batch_name, academic_year)`.** `index_batch_desc_batchID_academicYear`
looks like it provides one, but `batchID` is already the primary key, so that index guarantees
nothing. Meanwhile `DatabaseMerger` matches batches on exactly that pair. Two batches called
"Batch A" in one session are therefore possible today, and the merge would fold one into the
other, taking its membership and its lectures with it. V2 adds the constraint. It is the one most
likely to reject real data; review any violation by hand rather than relaxing it.

**Nothing in `attendance` records who marked it.** The source table is three columns: lecture,
student, present. Every row imported from history is genuinely unattributable, so it must be
imported with `created_by` NULL. Attributing old marks to a plausible professor would be
fabricating an audit record, which is worse than an honest gap.

## Expect the import to be rejected

V1 and V2 are applied to an empty database, so every statement in them succeeds. The consequence
lands in Phase 3 instead: the importer will be rejected by these constraints on any historical
row that violates one. That is the intent — such a row is a real defect in the data, and the
import report should surface it for review. Candidates to expect:

- two batches sharing a name within one academic year
- two whole-class lectures in the same slot for one course (SQLite allowed this: NULLs are
  distinct in a unique index, which is the gap `DaoLecture.findExisting` patches in Java)
- a `student_year` row whose stream its major department does not offer
- an `academic_year` referenced by a student or batch but with no row of its own
- a student whose minor or open elective is their own major department

## Deliberately not here yet

`id_remap` (Phase 3), `app_user` / `role` / `user_role` / `device_registration` and the row-level
security policies (Phase 4), and `audit_log` with its triggers (Phase 5). The three ID helper
functions in V1 — `id_is_structured`, `id_node`, `id_created_at` — are a port of `IdGenerator`'s
bit layout and are here early because Phase 4 needs `id_node()` to reject an uploaded row whose
key claims a device other than the one presenting the token.
