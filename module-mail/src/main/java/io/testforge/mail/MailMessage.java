package io.testforge.mail;

import java.util.List;

/** Content is available only in memory for explicit assertions. Never serialize this into evidence. */
public record MailMessage(String id, List<String> to, List<String> cc, List<String> bcc, String subject,
        String text, String html, List<Attachment> attachments) {
    public MailMessage {
        to = List.copyOf(to); cc = List.copyOf(cc); bcc = List.copyOf(bcc); attachments = List.copyOf(attachments);
    }
    public record Attachment(String partId, String fileName, String contentType, long size, String sha256) {
        @Override public String toString() { return "Attachment[content=<redacted>]"; }
    }
    boolean ownedBy(String recipient) {
        return to.size() == 1 && to.getFirst().equalsIgnoreCase(recipient) && cc.isEmpty() && bcc.isEmpty();
    }
    @Override public String toString() { return "MailMessage[content=<redacted>]"; }
}
