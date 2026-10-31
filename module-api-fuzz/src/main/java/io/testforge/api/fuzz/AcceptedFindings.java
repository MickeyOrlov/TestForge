package io.testforge.api.fuzz;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code forge.api-fuzz.accepted-findings}: findings a team has read and
 * decided to live with for now, so the gate can fail on what is new.
 *
 * <p>An entry is a finding group's key, {@code <check> <operation>}, as
 * {@code report.md} prints it — {@code not_a_server_error GET /items/{id}} —
 * optionally scoped to one spec with {@code <spec>:} in front, and optionally
 * followed by {@code  # why} (a ticket, an owner). The comment is for people;
 * matching ignores it.
 */
final class AcceptedFindings {

    private AcceptedFindings() {
    }

    /** Comments stripped, blanks dropped, whitespace collapsed; order kept. */
    static List<String> normalize(List<String> entries) {
        if (entries == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null) {
                continue;
            }
            int comment = entry.indexOf(" #");
            String value = (comment >= 0 ? entry.substring(0, comment) : entry).strip().replaceAll("\\s+", " ");
            if (!value.isEmpty() && !normalized.contains(value)) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    static boolean matches(String entry, String specId, String key) {
        return entry.equals(key) || entry.equals(specId + ":" + key);
    }

    static boolean accepted(List<String> entries, String specId, String key) {
        return entries.stream().anyMatch(entry -> matches(entry, specId, key));
    }
}
