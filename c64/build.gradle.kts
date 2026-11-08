// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    macosArm64()
    macosX64()
    linuxArm64()
    linuxX64()
    mingwX64()

    sourceSets {
        commonMain.dependencies { api(project(":cpu")) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

// ROMs and tapes remain local inputs, never resources packaged into the library.
tasks.withType<Test>().configureEach {
    systemProperty("c64slop.romDirectory", rootProject.file("roms").absolutePath)
    inputs.files(rootProject.file("roms/kernal.rom"), rootProject.file("roms/basic.rom"), rootProject.file("roms/chargen.rom"))
        .withPropertyName("c64Roms")
    testLogging { events("passed", "skipped", "failed") }
}
