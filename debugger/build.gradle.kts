// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-FileCopyrightText: 2026 The C64slop contributors
// SPDX-License-Identifier: BSD-3-Clause

plugins {
    kotlin("multiplatform")
}

// A Swing debugger for any 6502 machine. It knows the CPU (:cpu) and nothing
// else: a machine plugs in through DebugSession and DebugTarget, and passes in
// its own names for addresses. JVM-only, like :desktop.
kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies { api(project(":cpu")) }
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}
