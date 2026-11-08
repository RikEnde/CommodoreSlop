#!/bin/sh
# SPDX-FileCopyrightText: 2026 The Beebslop contributors
# SPDX-FileCopyrightText: 2026 The C64slop contributors
# SPDX-License-Identifier: BSD-3-Clause
#
# Build (if needed) and run the C64slop desktop emulator.
# Options are passed straight through, for example:
#   ./run.sh --scale 3
#   ./run.sh --prg programs/game.prg --joystick 2
#   ./run.sh --screenshot boot.png
#   ./run.sh --help
# Needs a JDK 17+: $JAVA_HOME, else .tools/jdk, else `java` on the PATH.
set -eu

cd "$(dirname "$0")"

if [ -z "${JAVA_HOME:-}" ] && [ -x .tools/jdk/bin/java ]; then
    JAVA_HOME="$PWD/.tools/jdk"
fi
# On macOS, ask for an installed JDK 17 or newer.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)"
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export JAVA_HOME
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi
# macOS has a /usr/bin/java stub even without a JDK, so check it actually runs.
if ! "$JAVA" -version >/dev/null 2>&1; then
    echo "run.sh: no working Java found. Install a JDK 17+ or set JAVA_HOME." >&2
    exit 1
fi

./gradlew -q :desktop:fatJar

exec "$JAVA" -Dapple.awt.application.name=C64slop -jar desktop/build/libs/c64slop.jar "$@"
