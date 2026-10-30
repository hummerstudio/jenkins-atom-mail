package com.jenkinschina.atom.mail;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mailer as an atom, written against the SDK: build a MIME message and hand it to {@code curl} for SMTP delivery.
 *
 * <p>Why curl and not javax.mail: an atom package should stay one jar, and every agent this runner targets has curl.
 * Building the MIME here also makes {@code dryRun} useful — the message is kept as an artifact, which is what the
 * smoke test asserts, so CI needs no SMTP server.
 */
public class MailAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String to = expand(context, context.getParam("to", ""));
        String cc = expand(context, context.getParam("cc", ""));
        String from = expand(context, context.getParam("from", ""));
        String subject = expand(context, workspace,
                context.getParam("subject", "[Jenkins] $PROJECT_NAME #$BUILD_NUMBER - $BUILD_STATUS"));
        String body = expand(context, workspace, context.getParam("body", ""));
        boolean html = context.getBooleanParam("html", false);
        String attachments = expand(context, context.getParam("attachments", ""));
        String host = context.getParam("smtpHost", "");
        String port = context.getParam("smtpPort", "587");
        boolean tls = context.getBooleanParam("tls", true);
        boolean dryRun = context.getBooleanParam("dryRun", false);

        if (to.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001, "no recipients: set 'to'");
        }
        if (!dryRun && host.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40002, "no SMTP host: set 'smtpHost'");
        }

        String user = orEmpty(context.getSecret("user"));
        String password = orEmpty(context.getSecret("password"));
        if (from.isBlank()) {
            from = user.isBlank() ? "jenkins@localhost" : user;
        }

        List<Path> files = new ArrayList<>();
        for (String part : attachments.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            Path file = workspace.resolve(name).normalize();
            if (!file.startsWith(workspace)) {
                return AtomResult.error(ErrorType.USER, 40003, "attachment outside the workspace: " + name);
            }
            if (!Files.isRegularFile(file)) {
                return AtomResult.error(ErrorType.USER, 40004, "attachment not found: " + name);
            }
            files.add(file);
        }

        String eml = message(from, to, cc, subject, body, html, files);
        Path emlFile = context.getDataDir().resolve("message.eml");
        Files.writeString(emlFile, eml, StandardCharsets.UTF_8);
        context.log("[mail] " + (dryRun ? "dry run: " : "") + "to=" + to + " subject=" + subject + " attachments="
                + files.size());

        if (!dryRun) {
            List<String> command = new ArrayList<>(List.of(
                    "curl", "-sS", "--url", (tls ? "smtps" : "smtp") + "://" + host + ":" + port));
            if (tls) {
                command.add("--ssl-reqd");
            }
            if (!user.isBlank()) {
                command.add("--user");
                command.add(user + ":" + password);
            }
            command.add("--mail-from");
            command.add(from);
            for (String recipient : recipients(to)) {
                command.add("--mail-rcpt");
                command.add(recipient);
            }
            for (String recipient : recipients(cc)) {
                command.add("--mail-rcpt");
                command.add(recipient);
            }
            command.add("--upload-file");
            command.add(emlFile.toString());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = process.waitFor();
            if (exit != 0) {
                return AtomResult.error(
                        ErrorType.THIRD_PART, 40005, "smtp delivery failed (curl exit " + exit + "): " + output);
            }
            if (!output.isEmpty()) {
                context.log("[mail] curl: " + output);
            }
        }

        return AtomResult.success().data("recipients", to).artifact("emlFile", emlFile.toString());
    }

    /** A MIME message: headers, then text/html body, then base64 attachments when there are any. */
    private static String message(
            String from, String to, String cc, String subject, String body, boolean html, List<Path> files)
            throws IOException {
        String boundary = "atom-" + Long.toHexString(System.nanoTime());
        StringBuilder out = new StringBuilder();
        out.append("From: ").append(from).append("\r\n");
        out.append("To: ").append(to).append("\r\n");
        if (!cc.isBlank()) {
            out.append("Cc: ").append(cc).append("\r\n");
        }
        out.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
        out.append("Date: ")
                .append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()))
                .append("\r\n");
        out.append("MIME-Version: 1.0\r\n");

        String contentType = html ? "text/html" : "text/plain";
        if (files.isEmpty()) {
            out.append("Content-Type: ").append(contentType).append("; charset=UTF-8\r\n");
            out.append("Content-Transfer-Encoding: base64\r\n\r\n");
            out.append(Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));
            out.append("\r\n");
            return out.toString();
        }

        out.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"\r\n\r\n");
        out.append("--").append(boundary).append("\r\n");
        out.append("Content-Type: ").append(contentType).append("; charset=UTF-8\r\n");
        out.append("Content-Transfer-Encoding: base64\r\n\r\n");
        out.append(Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)))
                .append("\r\n");
        for (Path file : files) {
            out.append("--").append(boundary).append("\r\n");
            out.append("Content-Type: application/octet-stream; name=\"")
                    .append(file.getFileName())
                    .append("\"\r\n");
            out.append("Content-Disposition: attachment; filename=\"")
                    .append(file.getFileName())
                    .append("\"\r\n");
            out.append("Content-Transfer-Encoding: base64\r\n\r\n");
            out.append(Base64.getMimeEncoder().encodeToString(Files.readAllBytes(file))).append("\r\n");
        }
        out.append("--").append(boundary).append("--\r\n");
        return out.toString();
    }

    /** RFC 2047 only when the subject is not plain ASCII. */
    private static String encodeHeader(String value) {
        boolean ascii = value.chars().allMatch(c -> c < 128);
        if (ascii) {
            return value;
        }
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static List<String> recipients(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    /**
     * email-ext's tokens, on top of the {@code ${ENV}} form the atom already had: the tail of the console log, the
     * failed tests from the JUnit XML in the workspace, and the commits since the previous build.
     */
    private String expand(AtomContext context, java.nio.file.Path workspace, String template) throws Exception {
        java.util.regex.Pattern token = java.util.regex.Pattern.compile(
                "\\$(?:\\{([A-Z_0-9]+)(?:,([^}]*))?}|([A-Z_0-9]{3,}))");
        java.util.regex.Matcher matcher = token.matcher(template == null ? "" : template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            String argument = matcher.group(2);
            String value = switch (name) {
                case "PROJECT_NAME" -> context.getParam("JOB_NAME", "");
                case "BUILD_NUMBER" -> context.getParam("BUILD_NUMBER", "");
                case "BUILD_URL" -> context.getParam("BUILD_URL", "");
                case "BUILD_STATUS", "BUILD_RESULT" -> context.getParam("BUILD_STATUS", "");
                case "BUILD_LOG_EXCERPT" -> logExcerpt(context, argument);
                case "FAILED_TESTS" -> failedTests(workspace);
                case "CHANGES" -> changes(workspace, argument);
                default -> null;
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return expand(context, out.toString());
    }

    private static String logExcerpt(AtomContext context, String argument) {
        String text;
        try {
            text = context.hasBuildLog() ? context.buildLog() : "";
        } catch (Exception e) {
            return "(no build log available)";
        }
        if (text.isBlank()) {
            return "(no build log available)";
        }
        int lines = 50;
        if (argument != null && !argument.isBlank()) {
            try {
                lines = Integer.parseInt(argument.trim());
            } catch (NumberFormatException ignored) {
                // a bad argument must not break an email
            }
        }
        String[] all = text.split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, all.length - lines); i < all.length; i++) {
            out.append(all[i]).append('\n');
        }
        String excerpt = out.toString();
        return excerpt.length() > 8000 ? excerpt.substring(excerpt.length() - 8000) : excerpt;
    }

    private static String failedTests(java.nio.file.Path workspace) throws Exception {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        try (var walk = java.nio.file.Files.walk(workspace, 12)) {
            for (java.nio.file.Path file : walk.filter(java.nio.file.Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("TEST-")
                            && path.toString().endsWith(".xml"))
                    .sorted().limit(50).toList()) {
                shown += appendFailing(file, out, 20 - shown);
            }
        } catch (Exception ignored) {
            // no reports is not an error for an email
        }
        return shown == 0 ? "(no failed tests found)" : out.toString();
    }

    private static int appendFailing(java.nio.file.Path file, StringBuilder out, int room) {
        int shown = 0;
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(file.toFile());
            var cases = document.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength() && shown < Math.max(0, room); i++) {
                var test = (org.w3c.dom.Element) cases.item(i);
                if (test.getElementsByTagName("failure").getLength() > 0
                        || test.getElementsByTagName("error").getLength() > 0) {
                    out.append("- ").append(test.getAttribute("classname")).append('#')
                            .append(test.getAttribute("name")).append('\n');
                    shown++;
                }
            }
        } catch (Exception ignored) {
            // an unreadable report contributes nothing
        }
        return shown;
    }

    private static String changes(java.nio.file.Path workspace, String argument) {
        int count = 10;
        if (argument != null && !argument.isBlank()) {
            try {
                count = Integer.parseInt(argument.trim());
            } catch (NumberFormatException ignored) {
                // default
            }
        }
        try {
            ProcessBuilder builder = new ProcessBuilder("git", "log",
                    "-" + Math.max(1, Math.min(count, 50)), "--pretty=- %s (%an)")
                    .directory(workspace.toFile()).redirectErrorStream(true);
            Process process = builder.start();
            String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            process.waitFor();
            return log.isBlank() ? "(no commits)" : log;
        } catch (Exception e) {
            return "(git not available)";
        }
    }

    private static String expand(AtomContext context, String template) {
        Matcher matcher = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}").matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = context.getParam(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
