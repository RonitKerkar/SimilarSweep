#!/usr/bin/env bash
# Bootstrap pinned Gradle using its official distribution and checksum.
set -euo pipefail
cd "$(dirname "$0")"
version=8.11.1
cache_dir="${XDG_CACHE_HOME:-$HOME/.cache}/similar-sweep"
mkdir -p "$cache_dir"
if [ ! -x "$cache_dir/gradle-$version/bin/gradle" ]; then
  curl -fL "https://services.gradle.org/distributions/gradle-$version-bin.zip" -o "$cache_dir/gradle.zip"
  expected=$(curl -fLs "https://services.gradle.org/distributions/gradle-$version-bin.zip.sha256")
  printf '%s  %s\n' "$expected" "$cache_dir/gradle.zip" | sha256sum -c -
  unzip -q "$cache_dir/gradle.zip" -d "$cache_dir"
fi
"$cache_dir/gradle-$version/bin/gradle" --no-daemon assembleDebug lintDebug
