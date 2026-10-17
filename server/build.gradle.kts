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
    jvmArgs("-Duser.timezone=UTC")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        events("failed")
    }
}
