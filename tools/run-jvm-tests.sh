#!/bin/bash
# Compiles the Android-free parts of Readle with Gradle's bundled Kotlin 2.0.21 compiler and runs the JUnit tests.
set -e
P=/home/claude/Readle/app/src
S=$(dirname "$0")
L=/opt/gradle-8.14.3/lib
OUT=$S/out
rm -rf "$OUT" && mkdir -p "$OUT/main" "$OUT/test"

COMPILER_CP=$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar
RUN_CP=$L/kotlin-stdlib-2.0.21.jar:$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar

kc() {
  java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect "$@" > "$OUT/kc.log" 2>&1
  local rc=$?
  grep -v "JAVA_TOOL_OPTIONS" "$OUT/kc.log" || true
  return $rc
}

echo "== compile main (pure JVM subset) =="
kc -cp "$RUN_CP" -d "$OUT/main" \
  "$S/stubs/RoomStubs.kt" \
  "$P/main/java/com/readle/app/data/db/Entities.kt" \
  $(find "$P/main/java/com/readle/app/domain" -name "*.kt") "$P/main/java/com/readle/app/ui/theme/DesignTokens.kt" "$P/main/java/com/readle/app/dict/WordNet.kt" \
  $(find "$P/main/java/com/readle/app/reader/reflow" -name '*.kt' ! -name 'ReaderJsBridge.kt' ! -name 'ReflowReader*.kt' ! -name 'ReflowController.kt' ! -name 'WebBookClient.kt')

echo "== compile tests =="
kc -cp "$OUT/main:$RUN_CP" -d "$OUT/test" \
  $(find "$P/test/java" -name '*.kt')

echo "== run tests =="
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | tr '\n' ' ')
java -cp "$OUT/test:$OUT/main:$RUN_CP" org.junit.runner.JUnitCore $CLASSES > "$OUT/junit.log" 2>&1 || true
grep -v "JAVA_TOOL_OPTIONS" "$OUT/junit.log" | grep -v "^\s*at \(org.junit\|java\.\|jdk\.\|sun\.\)" || true
