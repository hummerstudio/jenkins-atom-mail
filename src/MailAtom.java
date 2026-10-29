import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mailer as an atom: builds a MIME message and hands it to {@code curl} for SMTP delivery.
 *
 * <p>Why curl and not javax.mail: an atom package should be a jar plus task.json, and {@code curl} is already on every
 * agent that this runner targets. Doing the MIME by hand also keeps the message readable in the console when
 * {@code dryRun} is on — which is what makes this testable without an SMTP server.
 */
public class MailAtom {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(System.getenv("atom_data_dir"));
        String inputName = or(System.getenv("atom_data_input"), "input.json");
        String outputName = or(System.getenv("atom_data_output"), "output.json");
        String workspace = or(System.getenv("atom_workspace"), System.getProperty("user.dir"));
        String input = Files.readString(dir.resolve(inputName), StandardCharsets.UTF_8);
        Map<String, String> env = System.getenv();

        String to = expand(text(input, "to", ""), env);
        String cc = expand(text(input, "cc", ""), env);
        String from = expand(text(input, "from", ""), env);
        String subject = expand(text(input, "subject", "[Jenkins] ${JOB_NAME} #${BUILD_NUMBER}"), env);
        String body = expand(text(input, "body", ""), env);
        boolean html = Boolean.parseBoolean(text(input, "html", "false"));
        String attachments = expand(text(input, "attachments", ""), env);
        String host = text(input, "smtpHost", "");
        String port = text(input, "smtpPort", "587");
        boolean tls = Boolean.parseBoolean(text(input, "tls", "true"));
        boolean dryRun = Boolean.parseBoolean(text(input, "dryRun", "false"));

        if (to.isBlank()) {
            fail(dir, outputName, "no recipients: set 'to'", 40001);
            return;
        }
        if (!dryRun && host.isBlank()) {
            fail(dir, outputName, "no SMTP host: set 'smtpHost'", 40002);
            return;
        }

        Map<String, String> secrets = secrets(dir);
        String user = secrets.getOrDefault("user", "");
        String password = secrets.getOrDefault("password", "");
        if (from.isBlank()) {
            from = user.isBlank() ? "jenkins@localhost" : user;
        }

        List<Path> files = new ArrayList<>();
        for (String part : attachments.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            Path file = Path.of(workspace).resolve(name).normalize();
            if (!file.startsWith(Path.of(workspace))) {
                fail(dir, outputName, "attachment outside the workspace: " + name, 40003);
                return;
            }
            if (!Files.isRegularFile(file)) {
                fail(dir, outputName, "attachment not found: " + name, 40004);
                return;
            }
            files.add(file);
        }

        String eml = message(from, to, cc, subject, body, html, files);
        Path emlFile = dir.resolve("message.eml");
        Files.writeString(emlFile, eml, StandardCharsets.UTF_8);
        System.out.println("[mail] " + (dryRun ? "dry run: " : "") + "to=" + to + " subject=" + subject
                + " attachments=" + files.size());

        if (!dryRun) {
            List<String> command = new ArrayList<>(List.of("curl", "-sS", "--url",
                    (tls ? "smtps" : "smtp") + "://" + host + ":" + port));
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
                fail(dir, outputName, "smtp delivery failed (curl exit " + exit + "): " + output, 40005);
                return;
            }
            if (!output.isEmpty()) {
                System.out.println("[mail] curl: " + output);
            }
        }

        String json = "{\"status\":\"success\",\"data\":{"
                + "\"recipients\":{\"type\":\"string\",\"value\":\"" + escape(to) + "\"},"
                + "\"emlFile\":{\"type\":\"artifact\",\"value\":[\"" + escape(emlFile.toString()) + "\"]}}}";
        Files.writeString(dir.resolve(outputName), json, StandardCharsets.UTF_8);
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
        out.append(Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        for (Path file : files) {
            out.append("--").append(boundary).append("\r\n");
            out.append("Content-Type: application/octet-stream; name=\"")
                    .append(file.getFileName())
                    .append("\"\r\n");
            out.append("Content-Disposition: attachment; filename=\"")
                    .append(file.getFileName())
                    .append("\"\r\n");
            out.append("Content-Transfer-Encoding: base64\r\n\r\n");
            out.append(Base64.getMimeEncoder()
                            .encodeToString(Files.readAllBytes(file)))
                    .append("\r\n");
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
        return "=?UTF-8?B?"
                + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
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

    private static Map<String, String> secrets(Path dir) {
        Map<String, String> values = new LinkedHashMap<>();
        Path file = dir.resolve(".secrets.json");
        if (!Files.isRegularFile(file)) {
            return values;
        }
        try {
            Matcher matcher = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                values.putIfAbsent(unescape(matcher.group(1)), unescape(matcher.group(2)));
            }
        } catch (Exception e) {
            System.out.println("[mail] cannot read .secrets.json: " + e);
        }
        return values;
    }

    private static void fail(Path dir, String outputName, String message, int code) throws Exception {
        Files.writeString(
                dir.resolve(outputName),
                "{\"status\":\"error\",\"errorType\":1,\"errorCode\":" + code + ",\"message\":\"" + escape(message)
                        + "\"}",
                StandardCharsets.UTF_8);
        System.out.println("[mail] ERROR " + message);
        System.exit(1);
    }

    private static String expand(String template, Map<String, String> env) {
        Matcher matcher = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}").matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = env.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String text(String json, String key, String fallback) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return matcher.find() ? unescape(matcher.group(1)) : fallback;
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
