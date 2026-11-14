#!/usr/bin/env bash
# Select the arm64 Homebrew JDK 21. `/usr/libexec/java_home` on this machine only
# lists x86_64 JDKs (Temurin 21 x86_64, Zulu 8 ...), which would run under Rosetta.
#   source scripts/env.sh
export JAVA_HOME="${BETAVK_JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

arch="$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 | awk -F' = ' '/os.arch/ {print $2}')"
if [ "$arch" != "aarch64" ]; then
  echo "scripts/env.sh: $JAVA_HOME is '$arch', not aarch64" >&2
  return 1 2>/dev/null || exit 1
fi
echo "JAVA_HOME=$JAVA_HOME (os.arch=$arch)"
