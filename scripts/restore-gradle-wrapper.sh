#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
URL="https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar"
EXPECTED="a4b76b9530d66f5e68d973ea569d8e19de379189"
mkdir -p gradle/wrapper
curl --fail --location --silent --show-error --retry 3 "$URL" -o gradle/wrapper/gradle-wrapper.jar
ACTUAL="$(git hash-object gradle/wrapper/gradle-wrapper.jar)"
if [ "$ACTUAL" != "$EXPECTED" ]; then
  rm -f gradle/wrapper/gradle-wrapper.jar
  echo "Gradle wrapper JAR failed official Git blob SHA-1 verification." >&2
  exit 1
fi
echo "Verified official Gradle wrapper JAR restored."
