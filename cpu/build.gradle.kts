// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
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
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
    }
}
