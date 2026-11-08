// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

plugins {
    kotlin("multiplatform")
}

// The desktop front end is JVM-only (Swing). All emulation lives
// in the platform-independent :c64 and :cpu modules.
kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies { implementation(project(":c64")) }
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}

// The tests draw images but open no windows; headless AWT needs no display.
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
    systemProperty("c64slop.romDirectory", rootProject.file("roms").absolutePath)
}

// The ROMs go in the jar, so the emulator runs from any directory (--roms DIR
// still overrides them). Only the ones the emulator uses: roms/ may hold others.
val romResources = tasks.register<Sync>("romResources") {
    from(rootProject.file("roms")) {
        include("kernal.rom", "basic.rom", "chargen.rom")
        into("c64slop/roms")
    }
    into(layout.buildDirectory.dir("generated/romResources"))
}
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(romResources)

val mainCompilation = kotlin.jvm().compilations.getByName("main")
val mainClassName = "c64slop.desktop.MainKt"

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Open the emulator window. Pass options with --args=\"...\"."
    mainClass.set(mainClassName)
    classpath(mainCompilation.output.allOutputs)
    mainCompilation.runtimeDependencyFiles?.let { classpath(it) }
    workingDir = rootProject.projectDir
    jvmArgs("-Dapple.awt.application.name=C64slop")
}

tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Build a self-contained runnable jar in desktop/build/libs."
    archiveBaseName.set("c64slop")
    manifest { attributes("Main-Class" to mainClassName) }
    from(mainCompilation.output.allOutputs)
    from({ mainCompilation.runtimeDependencyFiles?.filter { it.name.endsWith(".jar") }?.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Native packages built by jpackage, with a trimmed Java runtime inside, so they
// run on machines without Java. jpackage builds only for the OS it runs on:
// packageApp works everywhere; packageDmg needs macOS. jpackage comes with
// JDK 17+; this uses the JDK that runs Gradle (JAVA_HOME).
val fatJar = tasks.named<Jar>("fatJar")
val packageInput = layout.buildDirectory.dir("jpackage/input")
val packageOutput = layout.buildDirectory.dir("jpackage")

val packageInputSync = tasks.register<Sync>("packageInput") {
    from(fatJar)
    into(packageInput)
}

// The icon from AppIcon.kt, in the format this OS's packages use; without one,
// jpackage gives the app Java's Duke icon.
val hostOs = org.gradle.internal.os.OperatingSystem.current()
val iconFile = layout.buildDirectory.file(
    "jpackage/icon/C64slop." + when {
        hostOs.isMacOsX -> "icns"
        hostOs.isWindows -> "ico"
        else -> "png"
    },
)
val appIcon = tasks.register<JavaExec>("appIcon") {
    description = "Write the app icon for jpackage."
    mainClass.set("c64slop.desktop.IconExportKt")
    classpath(mainCompilation.output.allOutputs)
    mainCompilation.runtimeDependencyFiles?.let { classpath(it) }
    args(iconFile.get().asFile.path)
    outputs.file(iconFile)
}

fun registerPackage(name: String, type: String, what: String) = tasks.register<Exec>(name) {
    group = "distribution"
    description = "Build $what in desktop/build/jpackage, with its own Java runtime."
    dependsOn(packageInputSync, appIcon)
    inputs.dir(packageInput)
    inputs.file(iconFile)
    val os = hostOs
    val jpackage = File(System.getProperty("java.home"), "bin/" + if (os.isWindows) "jpackage.exe" else "jpackage")
    val outputDir = packageOutput.get().asFile
    val appName = "C64slop"
    outputs.dir(outputDir.resolve(type))
    doFirst {
        if (!jpackage.canExecute()) throw GradleException("No jpackage at $jpackage. Run Gradle with a JDK 17+.")
        // jpackage refuses to overwrite earlier output.
        outputDir.resolve(type).deleteRecursively()
    }
    executable = jpackage.path
    args(
        "--type", type,
        "--name", appName,
        "--app-version", "1.0.0",
        "--vendor", "The C64slop contributors",
        "--description", "Commodore 64 emulator",
        "--input", packageInput.get().asFile.path,
        "--main-jar", fatJar.get().archiveFileName.get(),
        "--main-class", mainClassName,
        // Swing, AWT and javax.sound; jlink leaves the rest of the JDK out.
        "--add-modules", "java.desktop",
        "--java-options", "-Dapple.awt.application.name=$appName",
        "--icon", iconFile.get().asFile.path,
        "--dest", outputDir.resolve(type).path,
    )
    if (os.isMacOsX) args("--mac-package-name", appName)
}

registerPackage("packageApp", "app-image", "a standalone application (C64slop.app on macOS)")
registerPackage("packageDmg", "dmg", "a macOS disk image")
