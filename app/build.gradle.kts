import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    `generate-backendname-objects`
}

fun timestamp(time: Boolean): String {
    val dateFormat = SimpleDateFormat(if (time) "HHmm" else "yyyy-MM-dd")
    dateFormat.timeZone = TimeZone.getTimeZone("UTC")
    return dateFormat.format(Date())
}

/*
 * The git executable on PATH, or null when the machine has none.
 *
 * The lookup is a file test, not a process: Gradle reports an external
 * process started at configuration time as a configuration cache
 * problem, and that includes a process that cannot start at all.
 */
fun gitExecutable(): File? {
    val path = providers.environmentVariable("PATH").orNull ?: return null
    return path.split(File.pathSeparatorChar)
        .asSequence()
        .flatMap { dir -> listOf("git", "git.exe").asSequence().map { File(dir, it) } }
        .firstOrNull { it.isFile && it.canExecute() }
}

/** The standard output of `git [args]`, or "" when git is missing or fails. */
fun gitCommand(vararg args: String): String {
    val git = gitExecutable() ?: return ""
    return try {
        providers.exec {
            commandLine(git.absolutePath, *args)
            // A missing ref or remote must not fail the build or the configuration cache.
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    } catch (_: Exception) {
        ""
    }
}

fun idForSimon(): String {
    val mergeBase = gitCommand("merge-base", "simon/main", "main")
    if (mergeBase.isEmpty()) {
        return "UNOFFICIAL"
    }
    return gitCommand("rev-parse", "--short", mergeBase).ifEmpty { "UNOFFICIAL" }
}

/*
 * The `https://host/owner/repo` base of a git remote, or null when the
 * remote is not a web or SSH one. An SSH remote is rewritten to https so
 * the issue link works in a browser; a local path has no web form, and
 * the caller falls back to the upstream URL.
 */
fun issueBaseURL(remote: String): String? {
    val text = remote.trim()
    val uri = try {
        URI(text)
    } catch (_: Exception) {
        null
    }
    val host: String
    val path: String
    when {
        uri?.host != null && uri.scheme in setOf("http", "https") -> {
            host = uri.host
            path = uri.path.orEmpty()
        }
        uri?.host != null && uri.scheme == "ssh" -> {
            host = uri.host
            path = uri.path.orEmpty()
        }
        else -> {
            val scp = Regex("^[^@/]+@([^:/]+):(.+)$").find(text) ?: return null
            host = scp.groupValues[1]
            path = scp.groupValues[2]
        }
    }
    val clean = path.trim('/').removeSuffix(".git").trim('/')
    if (clean.split('/').size < 2) {
        return null
    }
    return "https://$host/$clean"
}

fun issuesURL(): String {
    val fallback = "https://github.com/diademoff/polished-puzzles"
    val repository = providers.environmentVariable("GITHUB_REPOSITORY").orNull
    val baseURL = when {
        !repository.isNullOrBlank() -> "https://github.com/$repository"
        else -> issueBaseURL(gitCommand("ls-remote", "--get-url", "origin")) ?: fallback
    }
    return "$baseURL/issues"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

android {
    namespace = "name.boyle.chris.sgtpuzzles"
    compileSdk = 36
    defaultConfig {
        applicationId = "name.boyle.chris.sgtpuzzles"
        minSdk = 21
        targetSdk = 36
        versionCode = 149
        versionName = timestamp(false)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "issues_url", issuesURL())
    }

    externalNativeBuild {
        ndkVersion = "29.0.14206865"
        cmake {
            path = File("src/main/jni/CMakeLists.txt")
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            versionNameSuffix = "-DEBUG-${idForSimon()}"
            ndk {
                isDebuggable = true
                debugSymbolLevel = "FULL"
            }
        }
        release {
            versionNameSuffix = "-${timestamp(true)}-${idForSimon()}"
            isMinifyEnabled = true
            ndk {
                isDebuggable = false
                debugSymbolLevel = "FULL"
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs {
            // see GamePlay.startGameGenProcess(...): this is the easiest way to get puzzlesgen installed and found per-architecture
            useLegacyPackaging = true
        }
    }
}

androidComponents {
    // These .sav files are needed by GamePlayScreenshotsTest.launchTestGame(...)
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addStaticSourceDirectory("src/main/jni/icons")
    }
}

// The renderer round-trip test reads the committed oracle traces.
tasks.withType<Test>().configureEach {
    systemProperty(
        "testdata",
        rootProject.layout.projectDirectory.dir("testdata").asFile.absolutePath,
    )
}

dependencies {
    implementation(project(":engine"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.gridlayout:gridlayout:1.1.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.webkit:webkit:1.14.0")

    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.20.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.annotation:annotation:1.9.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("tools.fastlane:screengrab:2.1.1")
}
