plugins {
    application
    jacoco
}

// Releases pass -PappVersion=1.2.3 (the git tag, minus the "v"). jpackage insists on a numeric
// version, so the fallback is numeric too.
version = (findProperty("appVersion") as String?) ?: "0.0.0"

java {
    toolchain {
        // FFM (java.lang.foreign) is stable on JDK 22+; project runs on Corretto 25.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    // Only the test framework is fetched; the application itself has zero dependencies.
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val appName = "ColonysSkeletonKey"
val mainClassName = "io.github.markosa84.colonysskeletonkey.AutoLockpick"

// The two flags the tool cannot run without: --enable-native-access silences the user32.dll FFM
// downcalls, and uiScale=1 makes Robot capture true device pixels on a DPI-scaled display. They
// apply equally to `gradlew run` and to the packaged exe - see CLAUDE.md, "Screen capture needs
// BOTH".
val requiredJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dsun.java2d.uiScale=1")

application {
    // Background console app: polls the F8 global hotkey, drives the game.
    mainClass = mainClassName
    applicationDefaultJvmArgs = requiredJvmArgs
}

// So the failure-frame captures/ folder lands in the project, not wherever Gradle was invoked.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

// The version belongs on the release zip, not on the jar inside it: lockpick.bat and jpackage both
// name the jar, and neither wants to track a version to find it.
tasks.jar {
    archiveVersion = ""
}

/**
 * The labelled frames the reader is calibrated against. They ship with the repository, so this
 * directory should always exist; the guard is only so a broken checkout fails inside the tests,
 * with a message, instead of dying here in the build script.
 */
val framesDir = layout.projectDirectory.dir("src/test/data/frames").asFile

tasks.test {
    useJUnitPlatform()
    // The tests analyse PNG files and never touch the screen; no display or DPI flag is needed.
    systemProperty("java.awt.headless", "true")
    // Deliberately not a classpath resource: every clean build would otherwise copy the whole
    // corpus into build/resources/test. They are still declared an input, so re-labelling a frame
    // or re-running scripts/shrink-frames.ps1 re-runs the reader gate instead of reporting
    // UP-TO-DATE off the last run's results.
    if (framesDir.isDirectory) {
        systemProperty("lockpick.frames.dir", framesDir.absolutePath)
        inputs.dir(framesDir).withPropertyName("frames").withPathSensitivity(PathSensitivity.RELATIVE)
    }
    testLogging {
        events("skipped", "failed")
    }
    finalizedBy(tasks.jacocoTestReport)
}

/**
 * Coverage. 0.8.14 is the first JaCoCo that reads Java 25 bytecode (class file major 69); an older
 * one does not fail politely, it fails on the first class it instruments.
 *
 * <p>The agent runs at test time and ships with nothing: the app still has zero dependencies.
 */
jacoco {
    toolVersion = "0.8.14"
}

/**
 * `win32` is the one package coverage does not apply to. It is the FFM boundary - six downcalls into
 * user32.dll and kernel32.dll - so a test of it would be a test of Windows, not of this project. It
 * is excluded from the report as well as the gate, so that what is measured and what is enforced are
 * the same thing.
 */
val coverageExclusions = listOf("**/win32/**")

/** The classes coverage is judged on: everything compiled, minus the exclusions above. */
fun JacocoReportBase.onlyTestableClasses() {
    classDirectories.setFrom(files(classDirectories.files.map {
        fileTree(it) { exclude(coverageExclusions) }
    }))
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    onlyTestableClasses()
    reports {
        html.required = true
        xml.required = true
    }
}

/**
 * The gate, wired into `check`. It is a ratchet, not an aspiration: the thresholds sit just under
 * what the suite actually reaches (94.5% line / 92.7% branch), so a change that stops testing
 * something fails the build - while a gate nobody can pass would only teach everyone to ignore it.
 *
 * The few percent that stay missing are the code a headless test JVM cannot reach:
 *
 *  - `AutoLockpick.main` owns a `Robot`, a `Toolkit` and an endless loop, and its display-owning
 *    helpers (`awtScale`, `screenSize`, `environment`) throw out of `GraphicsEnvironment` when
 *    headless. Everything it *decides* - the hotkey edge, the focus gate, which rectangle the game
 *    is drawing into, the banner - was lifted out of it and is covered.
 *  - The `Robot`-backed halves of the seams (`RobotKeyboard`'s taps, `GameScreen`'s grabber) are
 *    pure delegation to a `Robot` that cannot even be constructed headless. What they delegate to is
 *    covered through the seam.
 */
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    onlyTestableClasses()
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.92".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

/**
 * A self-contained Windows app image: our jar, a trimmed Java runtime, and a native launcher. A
 * player unzips it and runs the exe - there is no Java to install, and nothing to configure.
 *
 * jpackage ships with the JDK, so this needs no plugin and no dependency. The module list is what
 * `jdeps --print-module-deps` reports for the jar: FFM lives in java.base, and Robot/ImageIO in
 * java.desktop. That is the whole runtime.
 */
val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a self-contained Windows app image (bundled JRE; no Java needed to run it)."
    dependsOn(tasks.jar)

    val jpackage = javaToolchains.launcherFor(java.toolchain).get()
        .metadata.installationPath.file("bin/jpackage").asFile.absolutePath
    val dest = layout.buildDirectory.dir("jpackage").get().asFile
    val jar = tasks.jar.get().archiveFile.get().asFile
    val icon = layout.projectDirectory.file("packaging/$appName.ico").asFile

    inputs.file(jar)
    inputs.file(icon)
    outputs.dir(dest)

    doFirst {
        delete(dest) // jpackage refuses to write into an image that already exists
    }
    commandLine(
        buildList {
            add(jpackage)
            addAll(listOf("--type", "app-image"))
            addAll(listOf("--name", appName))
            addAll(listOf("--app-version", project.version.toString()))
            addAll(listOf("--input", jar.parentFile.absolutePath))
            addAll(listOf("--main-jar", jar.name))
            addAll(listOf("--main-class", mainClassName))
            addAll(listOf("--add-modules", "java.base,java.desktop"))
            requiredJvmArgs.forEach { addAll(listOf("--java-options", it)) }
            add("--win-console") // it prints, and waits for F8: it is a console app
            addAll(listOf("--icon", icon.absolutePath))
            addAll(listOf("--vendor", "markosa84"))
            addAll(listOf("--copyright", "Copyright (c) 2026 markosa84. MIT."))
            addAll(listOf("--description",
                "Unofficial fan-made lockpicking companion for Gothic 1 Remake"))
            addAll(listOf("--dest", dest.absolutePath))
        }
    )
}

/** What a release ships: the app image, zipped. */
val releaseZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Zips the app image into the artifact published on GitHub Releases."
    dependsOn(jpackageImage)
    from(layout.buildDirectory.dir("jpackage"))
    archiveFileName = "$appName-${project.version}-win64.zip"
    destinationDirectory = layout.buildDirectory.dir("release")
}
