#!/bin/bash
# Needs tools/out/main from run-jvm-tests.sh. Generates the reader pages with the real Kotlin code and drives them in headless Chromium.
set -e
S=$(cd "$(dirname "$0")" && pwd)
L=/opt/gradle-8.14.3/lib
CP=$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar
rm -rf "$S/out/gen" "$S/out/pages"; mkdir -p "$S/out/gen" "$S/out/pages"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$S/out/main:$L/kotlin-stdlib-2.0.21.jar" -d "$S/out/gen" "$S/GenPage.kt" 2>&1 | grep -v JAVA_TOOL || true
java -cp "$S/out/gen:$S/out/main:$L/kotlin-stdlib-2.0.21.jar" GenPageKt "$S/out/pages" 2>&1 | grep -v JAVA_TOOL
node "$S/webtest.js" "$S/out/pages"
