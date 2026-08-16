package io.testforge.reporting;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips absolute filesystem paths out of diagnostic text before it reaches the manifest.
 *
 * <p>Reporting output is published into CI artifacts, so it must not carry absolute paths:
 * they leak the local username and directory layout. The manifest already relativises the
 * artifact's own {@code path}, but exception messages are a second, easier-to-miss channel —
 * Java's filesystem exceptions put the absolute path in {@code getMessage()}, e.g.
 * {@code "/Users/alice/build/run/module/x.txt: Not a directory"}. That text was being copied
 * verbatim into {@code metadata["error"]} and into the manifest's {@code reportingProblems}.
 *
 * <p>Rather than discard the message — the reason ("Not a directory") is the useful part —
 * each absolute path is reduced to its file name, so the above becomes
 * {@code "x.txt: Not a directory"}.
 *
 * <p>Like everything else in reporting this is best-effort and never throws; on any failure
 * it returns a safe constant rather than risk propagating out of a diagnostic path.
 */
final class DiagnosticText {

    /** POSIX absolute paths: a leading '/' followed by at least one more segment. */
    private static final Pattern POSIX_ABSOLUTE = Pattern.compile("/(?:[^\\s/\\\\:;,'\"]+/)+[^\\s/\\\\:;,'\"]*");

    /** Windows absolute paths, e.g. C:\dir\file.txt or C:/dir/file.txt. */
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("[A-Za-z]:[\\\\/](?:[^\\s\\\\/:;,'\"]+[\\\\/])*[^\\s\\\\/:;,'\"]*");

    private DiagnosticText() {
    }

    /**
     * Returns {@code text} with any absolute filesystem path replaced by its file name.
     *
     * @param text raw diagnostic text, may be null
     * @return sanitised text, never null
     */
    static String sanitise(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        try {
            String result = replaceWithFileName(WINDOWS_ABSOLUTE, text);
            return replaceWithFileName(POSIX_ABSOLUTE, result);
        } catch (Throwable t) {
            // Never let sanitisation itself break a diagnostic path.
            return "<diagnostic text unavailable>";
        }
    }

    private static String replaceWithFileName(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String match = m.group();
            int cut = Math.max(match.lastIndexOf('/'), match.lastIndexOf('\\'));
            String fileName = (cut >= 0 && cut < match.length() - 1) ? match.substring(cut + 1) : "";
            m.appendReplacement(out, Matcher.quoteReplacement(fileName));
        }
        m.appendTail(out);
        return out.toString();
    }
}
