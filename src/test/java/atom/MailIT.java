package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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

    /** The message with one newline spelling, so the assertions do not depend on CRLF. */
    private static String eml(AtomResult run) {
        return run.file("message.eml").replace("\r\n", "\n");
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
