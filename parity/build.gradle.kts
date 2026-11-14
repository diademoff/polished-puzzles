import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The oracle binary. AGENTS.md uses build/oracle/puzzles-oracle.
// A relative -Poracle path is resolved against the repository root.
val oraclePath: String = providers.gradleProperty("oracle")
    .map { rootProject.file(it).absolutePath }
    .orElse(rootProject.layout.projectDirectory.file("build/oracle/puzzles-oracle").asFile.absolutePath)
    .get()

val testdataDir = rootProject.layout.projectDirectory.dir("testdata")

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // Declare the real inputs, so a rebuilt oracle or an edited golden
    // invalidates the test task instead of leaving it UP-TO-DATE.
    inputs.file(oraclePath)
    inputs.dir(testdataDir)
    systemProperty("oracle", oraclePath)
    systemProperty("testdata", testdataDir.asFile.absolutePath)
}

// Regenerates testdata/golden and testdata/manifest.json.
// tools/gen-goldens.sh wraps this task.
tasks.register<JavaExec>("genGoldens") {
    group = "parity"
    description = "Freezes params + seed -> DESC for every game"
    mainClass.set("name.boyle.chris.sgtpuzzles.parity.GenGoldens")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Doracle=$oraclePath")
    args(
        testdataDir.asFile.absolutePath,
        rootProject.layout.projectDirectory.file("UPSTREAM.lock").asFile.absolutePath,
    )
}
