package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mail atom, started the way the runner starts it, in dry-run mode: the MIME is built and kept as
 * {@code message.eml}, so no SMTP server and no network are involved.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made — the headers, the environment expansion, the attachment
 * part, the encoded non-ASCII subject, the tokens, and the two refusals — written as JUnit. The runner puts the build
 * environment into {@code input.json}, so that is where {@code JOB_NAME} and friends come from here.
 */
class MailIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("plain text, an expanded subject and one attachment that becomes a MIME part")
    void buildsTextWithAnAttachment() throws Exception {
        AtomResult run = atom.input("JOB_NAME", "parcel")
                .input("BUILD_NUMBER", "7")
                .file("reports/summary.txt", "artifact-body\n")
                .input("to", "dev@example.com, qa@example.com")
                .input("cc", "lead@example.com")
                .input("subject", "[${JOB_NAME}] build #${BUILD_NUMBER}")
                .input("body", "line one\nline two")
                .input("html", "false")
                .input("attachments", "reports/summary.txt")
                .input("smtpHost", "smtp.example.com")
                .input("smtpPort", "587")
                .input("tls", "true")
                .input("dryRun", "true")
                .run();

        run.assertOk();
        String eml = eml(run);
        assertTrue(eml.contains("To: dev@example.com, qa@example.com\n"), "To header:\n" + eml);
        assertTrue(eml.contains("\nCc: lead@example.com\n"), "Cc header:\n" + eml);
        assertTrue(eml.contains("Subject: [parcel] build #7\n"), "subject not expanded:\n" + eml);
        assertTrue(eml.contains("multipart/mixed"), "attachment was not turned into a MIME part:\n" + eml);
        assertTrue(eml.contains("filename=\"summary.txt\""), "attachment filename missing:\n" + eml);
        assertTrue(decodedBody(eml).contains("line one\nline two"), "body:\n" + decodedBody(eml));
        assertTrue(decodedBody(eml).contains("artifact-body"), "the attachment bytes are not in the message");
        assertEquals("success", run.status());
        assertTrue(run.file("output.json").contains("\"emlFile\""), "eml is not reported as an artifact");
    }

    @Test
    @DisplayName("a non-ASCII subject is RFC 2047 encoded and an HTML body is declared as such")
    void encodesANonAsciiSubject() throws Exception {
        AtomResult run = atom.input("BUILD_NUMBER", "7")
                .input("to", "dev@example.com")
                .input("from", "ci@example.com")
                .input("subject", "构建 #${BUILD_NUMBER} 失败")
                .input("body", "看日志")
                .input("html", "true")
                .input("smtpHost", "smtp.example.com")
                .input("dryRun", "true")
                .run();

        run.assertOk();
        String eml = eml(run);
        assertTrue(eml.contains("Subject: =?UTF-8?B?"), "non-ASCII subject was not encoded:\n" + eml);
        assertTrue(eml.contains("Content-Type: text/html; charset=UTF-8"), "html body not declared:\n" + eml);
        assertTrue(decodedBody(eml).contains("看日志"), "body:\n" + decodedBody(eml));
    }

    @Test
    @DisplayName("no recipients is a clear error, not a curl failure later")
    void refusesWithoutRecipients() throws Exception {
        AtomResult run = atom.input("to", "").input("subject", "x").input("body", "y")
                .input("smtpHost", "smtp.example.com").input("dryRun", "true").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no recipients"), "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a missing attachment is named, not a stack trace")
    void refusesAMissingAttachment() throws Exception {
        AtomResult run = atom.input("to", "dev@example.com").input("subject", "x").input("body", "y")
                .input("attachments", "reports/nope.txt")
                .input("smtpHost", "smtp.example.com").input("dryRun", "true").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("attachment not found: reports/nope.txt"),
                "attachment error is not readable: " + run.file("output.json"));
    }

    @Test
    @DisplayName("the log excerpt and the failed tests come from what the build left behind")
    void fillsTheBuildTokens() throws Exception {
        AtomResult run = atom.input("BUILD_STATUS", "FAILURE")
                .input("BUILD_NUMBER", "7")
                .input("JOB_NAME", "parcel")
                .env("atom_build_log", "build.log")
                .file("build.log", "first line\nERROR something broke\n")
                .file("target/surefire-reports/TEST-a.xml",
                        "<testsuite tests=\"1\" failures=\"1\"><testcase classname=\"demo.A\" name=\"red\">"
                                + "<failure message=\"boom\"/></testcase></testsuite>")
                .input("to", "dev@example.com")
                .input("dryRun", "true")
                .input("subject", "#${BUILD_NUMBER} ${BUILD_STATUS}")
                .input("body", "${BUILD_LOG_EXCERPT}\n${FAILED_TESTS}")
                .run();

        run.assertOk();
        String eml = eml(run);
        assertTrue(eml.contains("Subject: #7 FAILURE\n"), "subject:\n" + eml);
        String body = decodedBody(eml);
        assertTrue(body.contains("ERROR something broke"), "log excerpt:\n" + body);
        assertTrue(body.contains("demo.A#red"), "failed tests:\n" + body);
    }

    @Test
    @DisplayName("a non-dry run without an SMTP host is refused before curl is built")
    void refusesWithoutSmtpHost() throws Exception {
        AtomResult run = atom.input("to", "dev@example.com").input("subject", "x").input("body", "y")
                .input("dryRun", "false").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no SMTP host"), "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("an attachment that escapes the workspace is refused")
    void refusesAnAttachmentOutsideTheWorkspace() throws Exception {
        AtomResult run = atom.input("to", "dev@example.com").input("dryRun", "true")
                .input("attachments", "../secrets.txt").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("attachment outside the workspace"),
                "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("a real send builds the curl command line: TLS, credentials, every recipient, the eml file")
    void deliversThroughCurl() throws Exception {
        AtomResult run = atom.input("to", "a@example.com; b@example.com  c@example.com")
                .input("cc", "d@example.com,e@example.com")
                .input("subject", "built")
                .input("body", "hello")
                .input("smtpHost", "smtp.example.com")
                .input("smtpPort", "465")
                .input("tls", "true")
                .input("dryRun", "false")
                .file(".secrets.json", "{\"user\": \"ci@example.com\", \"password\": \"pw\"}")
                .bin("curl", "#!/bin/sh\nprintf '%s\\n' \"$@\" > curl-args.txt\necho \"250 2.0.0 Ok: queued\"\n")
                .run();

        run.assertOk();
        String args = run.file("curl-args.txt");
        assertTrue(args.contains("smtps://smtp.example.com:465"), "url: " + args);
        assertTrue(args.contains("--ssl-reqd"), "TLS flag missing: " + args);
        assertTrue(args.contains("--user\nci@example.com:pw"), "credentials missing: " + args);
        assertTrue(args.contains("--mail-rcpt\na@example.com"), "first recipient missing: " + args);
        assertTrue(args.contains("--mail-rcpt\nb@example.com"), "second recipient missing: " + args);
        assertTrue(args.contains("--mail-rcpt\nc@example.com"), "third recipient missing: " + args);
        assertTrue(args.contains("--mail-rcpt\nd@example.com"), "cc recipient missing: " + args);
        assertTrue(args.contains("--mail-from\nci@example.com"), "sender should be the credential user: " + args);
        assertTrue(args.contains("message.eml"), "the upload file is missing: " + args);
        assertTrue(run.console().contains("250 2.0.0 Ok: queued"), "curl output not logged: " + run.console());
    }

    @Test
    @DisplayName("a plain SMTP run without credentials leaves --user out and omits the TLS flag")
    void deliversWithoutTlsOrCredentials() throws Exception {
        AtomResult run = atom.input("to", "dev@example.com").input("from", "jenkins@example.com")
                .input("smtpHost", "localhost").input("smtpPort", "2525").input("tls", "false")
                .input("dryRun", "false")
                .bin("curl", "#!/bin/sh\nprintf '%s\\n' \"$@\" > curl-args.txt\nexit 0\n")
                .run();

        run.assertOk();
        String args = run.file("curl-args.txt");
        assertTrue(args.contains("smtp://localhost:2525"), "url: " + args);
        assertFalse(args.contains("--user"), "credentials were sent although none were configured: " + args);
        assertFalse(args.contains("--ssl-reqd"), "TLS flag on a plain connection: " + args);
        assertTrue(args.contains("--mail-from\njenkins@example.com"), "sender: " + args);
    }

    @Test
    @DisplayName("curl failing to deliver is a third-party error carrying curl's own message")
    void reportsAFailedDelivery() throws Exception {
        AtomResult run = atom.input("to", "dev@example.com").input("smtpHost", "smtp.example.com")
                .input("dryRun", "false")
                .bin("curl", "#!/bin/sh\necho 'curl: (67) Authentication failed'\nexit 67\n")
                .run();

        run.assertRefused();
        String output = run.file("output.json");
        assertTrue(output.contains("smtp delivery failed (curl exit 67)"), "message: " + output);
        assertTrue(output.contains("Authentication failed"), "curl output dropped: " + output);
    }

    @Test
    @DisplayName("PROJECT_NAME and BUILD_URL expand, an unknown token is left alone")
    void expandsTheRemainingTokens() throws Exception {
        AtomResult run = atom.input("JOB_NAME", "parcel").input("BUILD_URL", "https://ci.example.com/job/parcel/7/")
                .input("BUILD_STATUS", "SUCCESS")
                .input("to", "dev@example.com").input("dryRun", "true")
                .input("subject", "$PROJECT_NAME ${BUILD_URL} ${BUILD_RESULT}")
                .input("body", "unknown: ${NOT_A_DECLARED_TOKEN} and $ALSO_UNKNOWN")
                .run();

        run.assertOk();
        String eml = eml(run);
        assertTrue(eml.contains("Subject: parcel https://ci.example.com/job/parcel/7/ SUCCESS\n"), "subject:\n" + eml);
        String body = decodedBody(eml);
        assertTrue(body.contains("${NOT_A_DECLARED_TOKEN}"), "an unknown braced token was eaten: " + body);
        assertTrue(body.contains("$ALSO_UNKNOWN"), "an unknown bare token was eaten: " + body);
    }

    @Test
    @DisplayName("CHANGES reads the git log of the workspace, and says so when there is no git")
    void fillsChangesFromTheGitLog() throws Exception {
        Path repo = Files.createDirectories(Path.of(System.getProperty("atom.scratch"))
                .resolve("changes-" + System.nanoTime()));
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@t");
        git(repo, "config", "user.name", "tester");
        Files.writeString(repo.resolve("a.txt"), "a\n");
        git(repo, "add", ".");
        git(repo, "commit", "-qm", "first change");
        Files.writeString(repo.resolve("a.txt"), "b\n");
        git(repo, "commit", "-qam", "second change");

        AtomResult run = atom.input("to", "dev@example.com").input("dryRun", "true")
                .input("atomWorkspace", repo.toString())
                .input("subject", "changes")
                .input("body", "${CHANGES,1}").run();

        run.assertOk();
        String body = decodedBody(eml(run));
        assertTrue(body.contains("- second change (tester)"), "git log not rendered: " + body);
        assertFalse(body.contains("first change"), "the count argument was ignored: " + body);

        AtomResult bad = atom.input("to", "dev@example.com").input("dryRun", "true")
                .input("atomWorkspace", repo.toString())
                .input("subject", "changes").input("body", "${CHANGES,notanumber}").run();
        bad.assertOk();
        assertTrue(decodedBody(eml(bad)).contains("first change"), "the default count was not used");

        AtomResult noGit = atom.input("to", "dev@example.com").input("dryRun", "true")
                .input("atomWorkspace", repo.toString())
                .input("subject", "changes").input("body", "${CHANGES}")
                .env("PATH", "").run();
        noGit.assertOk();
        assertTrue(decodedBody(eml(noGit)).contains("(git not available)"),
                "a missing git was not reported: " + decodedBody(eml(noGit)));
    }

    @Test
    @DisplayName("a bad excerpt length keeps the default and an empty log is named")
    void handlesTheExcerptArgument() throws Exception {
        AtomResult empty = atom.input("to", "dev@example.com").input("dryRun", "true")
                .env("atom_build_log", "build.log").file("build.log", "")
                .input("body", "${BUILD_LOG_EXCERPT}").run();
        empty.assertOk();
        assertTrue(decodedBody(eml(empty)).contains("(no build log available)"),
                "an empty log was not named: " + decodedBody(eml(empty)));

        AtomResult run = atom.input("to", "dev@example.com").input("dryRun", "true")
                .env("atom_build_log", "build.log")
                .file("build.log", "one\ntwo\nthree\n")
                .input("body", "${BUILD_LOG_EXCERPT,notanumber}").run();
        run.assertOk();
        String body = decodedBody(eml(run));
        assertTrue(body.contains("one") && body.contains("three"),
                "a bad argument dropped the excerpt: " + body);
    }

    @Test
    @DisplayName("a failed test with an <error> counts, and an unreadable report is skipped")
    void readsErrorsAndSkipsBrokenReports() throws Exception {
        AtomResult run = atom.input("to", "dev@example.com").input("dryRun", "true")
                .file("target/surefire-reports/TEST-b.xml",
                        "<testsuite><testcase classname=\"demo.B\" name=\"err\"><error message=\"npe\"/>"
                                + "</testcase></testsuite>")
                .file("target/surefire-reports/TEST-c.xml", "this is not xml")
                .file("target/surefire-reports/notes.txt", "ignored")
                .input("body", "${FAILED_TESTS}").run();

        run.assertOk();
        String body = decodedBody(eml(run));
        assertTrue(body.contains("demo.B#err"), "an <error> was not reported: " + body);
    }

    /** The message with one newline spelling, so the assertions do not depend on CRLF. */
    private static String eml(AtomResult run) {
        return run.file("message.eml").replace("\r\n", "\n");
    }

    /** Runs one git command in {@code dir}; the fixture uses this instead of a library so it is a real repository. */
    private static String git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }

    /** The decoded text of the message: every base64 part, joined — what a mail client would show. */
    private static String decodedBody(String eml) {
        StringBuilder text = new StringBuilder();
        String[] parts = eml.split("(?m)^--");
        for (String part : parts) {
            int header = part.indexOf("Content-Transfer-Encoding: base64");
            if (header < 0) {
                continue;
            }
            int body = part.indexOf("\n\n", header);
            String encoded = body < 0 ? "" : part.substring(body + 2).replaceAll("(?m)^--.*$", "").strip();
            if (encoded.isEmpty()) {
                continue;
            }
            try {
                text.append(new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // a part this test does not read
            }
        }
        return text.toString();
    }
}
