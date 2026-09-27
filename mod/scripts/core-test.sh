#!/bin/sh
# Compiles and runs the terrain core tests with only a JDK (no Gradle,
# no Minecraft).  Usage: mod/scripts/core-test.sh [testdata dir]
set -e
here=$(cd "$(dirname "$0")/.." && pwd)
data=${1:-$here/build/testdata}
out=$here/build/core-classes
rm -rf "$out" && mkdir -p "$out"
javac -d "$out" $(find "$here/src/main/java/io/github/lazytive/alosearth/core" "$here/src/test/java" -name '*.java')
java -cp "$out" io.github.lazytive.alosearth.core.CoreTests "$data"
