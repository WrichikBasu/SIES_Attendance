plugins {
    `java-library`
    // The list of query cases the two dialects are compared over lives in
    // src/testFixtures, so the app's dump test and the server's dump test iterate one
    // list rather than two copies of it that could quietly diverge.
    `java-test-fixtures`
}

/*
 * Domain code with no engine and no platform in it.
 *
 * Everything here is compiled twice: once into the Android app, once into the server.
 * That is the whole point of the module — AcademicRules, the enums and the filter
 * objects are the rules of the college, and a second copy of them on the server would
 * drift from the first within a term.
 *
 * Two things are therefore deliberately absent:
 *
 *   - Android. No android.* and no androidx.room.*, so the module compiles on a plain
 *     JVM. The annotations are the one exception, and they are compileOnly.
 *   - SQL. The dialects live with their engines: SqlBits and StatsQueryBuilder stay in
 *     the app because they speak SQLite, and their PostgreSQL counterparts are in
 *     :server. StatsGroupBy is here, but only as the list of dimensions a report can
 *     be grouped by; each side supplies its own expressions for them.
 */
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Java 17 bytecode, matching the app's compileOptions. The server reads it happily;
    // the reverse would not be true.
    options.release = 17
    options.compilerArgs.add("-Xlint:all")
}

dependencies {
    // Annotations only, and CLASS-retained, so neither consumer needs them at run time.
    // Keeping them means the files moved here from the app needed no edits.
    compileOnly(libs.annotation.jvm)

    testImplementation(libs.junit)
    testCompileOnly(libs.annotation.jvm)

    testFixturesCompileOnly(libs.annotation.jvm)
}

tasks.named<Test>("test") {
    testLogging {
        events("failed")
    }
}
