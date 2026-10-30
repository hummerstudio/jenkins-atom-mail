#!/usr/bin/env bash
# Run the atom in dry-run mode and assert the MIME it builds: no SMTP server, no network, CI-friendly.
set -euo pipefail
cd "$(dirname "$0")/.."

export BUILD_NUMBER=7 JOB_NAME=parcel

./build.sh >/dev/null
jar="$(cd dist/mail && pwd)/mail.jar"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
work="$(mktemp -d)"


fail() { echo "smoke failed: $*" >&2; exit 1; }

run() { # run <dir>
  local dir="$1"
  python3 - "$dir/input.json" "$dir" <<'JSONFIX'
import json, os, sys
path, workspace = sys.argv[1], sys.argv[2]
given = {}
if os.path.exists(path) and os.path.getsize(path):
    given = json.load(open(path))
# the runner writes its environment into input.json first, then the job's inputs on top
data = dict(os.environ)
data.update(given)
data.setdefault("atom_workspace", workspace)
json.dump(data, open(path, "w"))
JSONFIX
  ( cd "$dir" && atom_data_dir="$dir" atom_data_input=input.json atom_data_output=output.json \
      atom_workspace="$dir" JOB_NAME=parcel BUILD_NUMBER=7 \
      "$java_bin" -cp "$jar" com.jenkinschina.atom.sdk.AtomRunner )
}

# 1. plain text, environment expansion, one attachment
dir="$work/basic"; mkdir -p "$dir/reports"
printf 'artifact-body\n' >"$dir/reports/summary.txt"
cat >"$dir/input.json" <<'JSON'
{"to": "dev@example.com, qa@example.com", "cc": "lead@example.com", "from": "",
 "subject": "[${JOB_NAME}] build #${BUILD_NUMBER}", "body": "line one\nline two", "html": "false",
 "attachments": "reports/summary.txt", "smtpHost": "smtp.example.com", "smtpPort": "587",
 "tls": "true", "user": "", "password": "", "dryRun": "true"}
JSON
run "$dir" >"$dir/log.txt" 2>&1 || fail "dry run failed: $(cat "$dir/log.txt")"
eml="$dir/message.eml"
grep -q "^To: dev@example.com, qa@example.com" "$eml" || fail "To header"
grep -q "^Cc: lead@example.com" "$eml" || fail "Cc header"
grep -q "^Subject: \[parcel\] build #7" "$eml" || fail "subject not expanded: $(grep ^Subject "$eml")"
grep -q 'multipart/mixed' "$eml" || fail "attachment was not turned into a MIME part"
grep -q 'filename="summary.txt"' "$eml" || fail "attachment filename missing"
grep -q '"status":"success"' "$dir/output.json" || fail "output is not a success"
grep -q '"emlFile"' "$dir/output.json" || fail "eml is not reported as an artifact"

# 2. non-ASCII subject is encoded, not sent raw
dir="$work/utf8"; mkdir -p "$dir"
cat >"$dir/input.json" <<'JSON'
{"to": "dev@example.com", "cc": "", "from": "ci@example.com",
 "subject": "构建 #${BUILD_NUMBER} 失败", "body": "看日志", "html": "true",
 "attachments": "", "smtpHost": "smtp.example.com", "smtpPort": "587", "tls": "true",
 "user": "", "password": "", "dryRun": "true"}
JSON
run "$dir" >/dev/null 2>&1 || fail "utf8 dry run failed"
grep -q '^Subject: =?UTF-8?B?' "$dir/message.eml" || fail "non-ASCII subject was not encoded"
grep -q 'Content-Type: text/html; charset=UTF-8' "$dir/message.eml" || fail "html body not declared"

# 3. no recipients is a clear error, not a curl failure later
dir="$work/empty"; mkdir -p "$dir"
cat >"$dir/input.json" <<'JSON'
{"to": "", "cc": "", "from": "", "subject": "x", "body": "y", "html": "false",
 "attachments": "", "smtpHost": "smtp.example.com", "smtpPort": "587", "tls": "true",
 "user": "", "password": "", "dryRun": "true"}
JSON
if run "$dir" >/dev/null 2>&1; then fail "missing recipients must fail"; fi
grep -q 'no recipients' "$dir/output.json" || fail "message is not readable: $(cat "$dir/output.json")"

# 4. a missing attachment is named, not a stack trace
dir="$work/missing"; mkdir -p "$dir"
cat >"$dir/input.json" <<'JSON'
{"to": "dev@example.com", "cc": "", "from": "", "subject": "x", "body": "y", "html": "false",
 "attachments": "reports/nope.txt", "smtpHost": "smtp.example.com", "smtpPort": "587",
 "tls": "true", "user": "", "password": "", "dryRun": "true"}
JSON
if run "$dir" >/dev/null 2>&1; then fail "missing attachment must fail"; fi
grep -q 'attachment not found: reports/nope.txt' "$dir/output.json" || fail "attachment error is not readable"

# tokens: log excerpt and failed tests come from what the build left behind
dir="$(mktemp -d)"; mkdir -p "$dir/target/surefire-reports"
printf '<testsuite tests="1" failures="1"><testcase classname="demo.A" name="red"><failure message="boom"/></testcase></testsuite>' \
  >"$dir/target/surefire-reports/TEST-a.xml"
printf 'first line\nERROR something broke\n' >"$dir/build.log"
printf '{"to": "dev@example.com", "dryRun": "true", "subject": "#$BUILD_NUMBER $BUILD_STATUS", "body": "$BUILD_LOG_EXCERPT\\n$FAILED_TESTS"}' >"$dir/input.json"
export atom_build_log="$dir/build.log" BUILD_STATUS=FAILURE JOB_NAME=parcel
run "$dir" >"$dir/log.txt" 2>&1 || fail "token run failed: $(cat "$dir/log.txt")"
unset atom_build_log BUILD_STATUS
eml=$(find "$dir" -name message.eml | head -1)
[ -n "$eml" ] || fail "no message.eml"
python3 - "$eml" <<'PY'
import email, sys
msg = email.message_from_bytes(open(sys.argv[1], 'rb').read())
body = msg.get_payload(decode=True).decode()
assert "#7 FAILURE" in msg['Subject'], msg['Subject']
assert "ERROR something broke" in body, body
assert "demo.A#red" in body, body
PY

echo "smoke ok: text+attachment, utf8 subject, tokens, no recipients, missing attachment"

