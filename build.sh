#!/usr/bin/env bash
# Build the atom against the SDK and pack ONE jar: the atom's classes, the SDK and its service registration.
#
#   ./build.sh                      # uses ../jenkins-atom-sdk/target/*.jar
#   SDK_JAR=/path/to/jenkins-atom-sdk-0.2.0.jar ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"; JAR="$JAVA_HOME/bin/jar"
else
  JAVAC=javac; JAR=jar
fi

if [ -z "${SDK_JAR:-}" ]; then
  for candidate in ../jenkins-atom-sdk/target/jenkins-atom-sdk-0.2.0.jar \
                   ./jenkins-atom-sdk/target/jenkins-atom-sdk-0.2.0.jar; do
    [ -f "$candidate" ] && { SDK_JAR="$candidate"; break; }
  done
fi
SDK_JAR="${SDK_JAR:-}"
[ -f "${SDK_JAR:-/nonexistent}" ] || {
  echo "找不到 SDK jar：${SDK_JAR:-<unset>}" >&2
  echo "先构建 SDK：(cd ../jenkins-atom-sdk && ./build.sh)，或用 SDK_JAR=... 指定" >&2
  exit 1
}
SDK_JAR="$(cd "$(dirname "$SDK_JAR")" && pwd)/$(basename "$SDK_JAR")"

classes="$(mktemp -d)"; sdk="$(mktemp -d)"
trap 'rm -rf "$classes" "$sdk"' EXIT
"$JAVAC" -cp "$SDK_JAR" -encoding UTF-8 -d "$classes" $(find src/main/java -name '*.java')
mkdir -p "$classes/META-INF/services"
cp src/main/resources/META-INF/services/* "$classes/META-INF/services/"
( cd "$sdk" && "$JAR" xf "$SDK_JAR" && rm -rf META-INF/MANIFEST.MF META-INF/services )

rm -rf dist/mail
mkdir -p dist/mail
"$JAR" --create --file dist/mail/mail.jar -C "$classes" . -C "$sdk" .
cp task.json dist/mail/
[ -f meta.json ] && cp meta.json dist/mail/
echo "built dist/mail: $(ls dist/mail | tr '\n' ' ')"
