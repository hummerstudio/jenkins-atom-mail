#!/usr/bin/env bash
# Compile the atom and pack dist/mail/ — the directory tools/publish-atom.sh expects.
set -euo pipefail
cd "$(dirname "$0")"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAR="$JAVA_HOME/bin/jar"
else
  JAVAC=javac
  JAR=jar
fi

classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
"$JAVAC" -encoding UTF-8 -d "$classes" src/MailAtom.java

rm -rf dist/mail
mkdir -p dist/mail
"$JAR" --create --file dist/mail/mail.jar -C "$classes" .
cp task.json dist/mail/
[ -f meta.json ] && cp meta.json dist/mail/

echo "built dist/mail: $(ls dist/mail | tr '\n' ' ')"
