#!/usr/bin/env bash
# Prints the path of the app jar in its EXTRACTED layout (flat classpath), extracting it if the fat jar is newer.
# Why: the Spring Boot fat-jar classloader synchronizes on nested-jar monitors while loading classes. Under a cold start
# with hundreds of simultaneous virtual threads every carrier thread can end up pinned waiting on that monitor and the app
# wedges (found by thread dump). The container image (Jib) already uses a flat classpath, so benchmarks must too.
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=$(ls target/payflow-ledger-*.jar | grep -v original | head -1)
DIR=target/extracted
if [ ! -f "$DIR/.stamp" ] || [ "$JAR" -nt "$DIR/.stamp" ]; then
  rm -rf "$DIR"
  java -Djarmode=tools -jar "$JAR" extract --destination "$DIR" > /dev/null
  touch "$DIR/.stamp"
fi
echo "$DIR/$(basename "$JAR")"
