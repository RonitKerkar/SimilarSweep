#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
java com.sun.tools.javac.Main -d "$test_dir" app/src/main/java/com/ronit/similarsweep/Matcher.java tests/MatcherTest.java
java -cp "$test_dir" MatcherTest
