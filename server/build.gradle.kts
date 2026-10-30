plugins {
    java
    alias(libs.plugins.spring.boot)
}

/*
 * The service, and the PostgreSQL migrations it applies.
 *
 * Flyway reads from classpath:db/migration, which is where
 * src/main/resources/db/migration lands, so the SQL written in Phase 1 needs no
 * re-pointing: the migrations the throwaway container in tools/verify-schema.sh
 * applies are byte-for-byte the ones the service applies on startup.
 *
 * Spring Data JDBC and JdbcClient rather than JPA. The access patterns here are
 * hand-written SQL with composite keys and raw statistics queries, assembled from a
 * filter object at run time, which is exactly where an ORM fights you. There is no
 * entity graph to map and nothing to lazy-load.
 */
java {
    // Java 25, the current LTS, which Spring Boot 4.1 supports (17 is its floor, 25 its
    // ceiling). Note what this does NOT apply to: :shared still emits Java 17 bytecode,
    // because the Android app has to read it too. The server reads 17 happily; the
    // reverse would not be true.
    //
    // The deployment JVM must therefore be 25 as well. Dropping back is one number here
    // and nothing else, since no language feature above 17 is used anywhere.
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:all")
}

dependencies {
    // The enums, AcademicRules and the filter objects, the same compiled classes the app
    // uses. The PostgreSQL query builders here take the very StatsFilter the statistics
    // screen builds, which is what keeps the two sides answering the same questions.
    implementation(project(":shared"))
    compileOnly(libs.annotation.jvm)

    // Versions come from the BOM rather than from the io.spring.dependency-management
    // plugin, so everything this module resolves is visible in libs.versions.toml.
    implementation(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.actuator)

    // Boot 4 no longer auto-configures Flyway from flyway-core alone: the
    // auto-configuration moved into its own module, and without it the migrations
    // are configured, present, and never applied.
    implementation(libs.spring.boot.flyway)
    implementation(libs.flyway.core)
    // Flyway 10 onward keeps each engine in its own module; without this one it refuses
    // to recognise a PostgreSQL connection at all.
    implementation(libs.flyway.postgresql)

    runtimeOnly(libs.postgresql)

    // Reading a device's backup means opening a SQLite file, which this is the only way to
    // do on a plain JVM. The shared schema migrations then run against it through
    // JdbcSqliteDb, so an old backup is brought up to the current schema by the very code
    // the phone would have used.
    implementation(libs.sqlite.jdbc)

    // Passwords. See the note in libs.versions.toml for why this is the crypto
    // module rather than the security starter.
    implementation(libs.spring.security.crypto)
    runtimeOnly(libs.bouncycastle)

    testImplementation(libs.spring.boot.starter.test)
    // The named query cases, shared with the app's dump test so that both sides really do
    // generate a statement for the same filter.
    testImplementation(testFixtures(project(":shared")))
    testCompileOnly(libs.annotation.jvm)
}

// The schema never relies on an ambient time zone — session_year_of takes it as a
// parameter precisely so that it cannot — and neither should the service. Set here as
// well as in production, so that a local run cannot pass on a machine in Asia/Kolkata
// and fail on a server in UTC.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs(
        "-Duser.timezone=UTC",
        // sqlite-jdbc unpacks and loads a native library, which Java 25 warns about and a
        // later release will refuse outright unless it is permitted here. Only the importer
        // touches SQLite, but bootRun is how the importer is run.
        "--enable-native-access=ALL-UNNAMED",
    )
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // As above: the importer's tests open real SQLite files.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        events("failed")
    }
}

/*
 * The artefact that gets deployed.
 *
 * One jar, named without a version on purpose: the systemd unit in deploy/ names a path,
 * and a unit that has to be edited on every release is a unit that will one day point at
 * a jar nobody deployed. What was deployed is answered by the release, not by the filename.
 *
 * Naming the main class rather than letting the plugin find it, because this module has
 * three entry points that are not it -- BootstrapCommand, ImportCommand and MigrateCommand
 * all run under their own profile through this same class, and a reader looking for "what
 * starts" should find the answer here.
 *
 *   ./gradlew :server:bootJar     -> build/libs/sies-attendance.jar
 */
springBoot {
    mainClass = "in.basulabs.siesattendance.server.SiesAttendanceServer"
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "sies-attendance.jar"
}

// Writes a sample .siesbak, so the importer can be tried by hand before there is a real
// device backup to try it on. Uses the test fixtures, which is where the knowledge of how
// to build one lives.
//
//   ./gradlew :server:sampleBackup
//   ./gradlew :server:sampleBackup -Psample.version=6   # an old schema, to be migrated
tasks.register<JavaExec>("sampleBackup") {
    group = "verification"
    description = "Writes a sample device backup to build/sample/."
    mainClass = "in.basulabs.siesattendance.server.importer.SampleBackup"
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args(
        layout.buildDirectory.dir("sample").get().asFile.path,
        providers.gradleProperty("sample.version").getOrElse("10"),
    )
}
