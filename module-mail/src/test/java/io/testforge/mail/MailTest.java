package io.testforge.mail;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MailTest {
    private MailProperties properties() { return new MailProperties(true, "http://localhost:8025", null, null,
            Duration.ofMillis(100), Duration.ofMillis(150), Duration.ofMillis(10), 0, 0); }
    static class Fake implements MailBackend {
        final Map<String, MailMessage> messages = new HashMap<>();
        final List<String> deleted = new ArrayList<>();
        boolean failDelete, returnForeign;
        int attachmentRequests;
        public List<String> search(String recipient, Duration budget) {
            return messages.values().stream().filter(m -> returnForeign || m.to().contains(recipient)).map(MailMessage::id).toList();
        }
        public MailMessage fetch(String id, Duration budget) { return messages.get(id); }
        public byte[] attachment(String id, String partId, Duration budget) { attachmentRequests++; return new byte[] {1}; }
        public void delete(List<String> ids, Duration budget) {
            if (failDelete) throw new IllegalStateException("Mailpit request failed");
            deleted.addAll(ids); ids.forEach(messages::remove);
        }
        public void close() { }
        void message(String id, String to) {
            messages.put(id, new MailMessage(id, List.of(to), List.of(), List.of(), "private-subject",
                    "OTP: private-token", "<b>private-token</b>", List.of(new MailMessage.Attachment("1", "private-file", "text/plain", 1, "hash"))));
        }
    }
    @Test void configurationIsInertAndMissingTargetFailsBeforeOpeningAScope() {
        var context = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TestForgeMailAutoConfiguration.class));
        context.run(c -> assertThat(c).doesNotHaveBean(Mailpit.class));
        context.withPropertyValues("forge.mail.enabled=true").run(c -> {
            assertThat(c).hasSingleBean(Mailpit.class);
            assertThatThrownBy(() -> c.getBean(Mailpit.class).open()).hasMessageContaining("api-url");
        });
        for (String bad : List.of("http://user:secret@host", "https://host?token=secret", "file:///tmp/mail")) {
            assertThatThrownBy(() -> new MailProperties(true, bad, null, null, null, null, null, 0, 0))
                    .hasMessageNotContaining("secret");
        }
    }
    @Test void scopesAreIsolatedAndNeverDeleteUnrelatedMail() {
        Fake fake = new Fake(); fake.message("foreign", "someone@else.test");
        try (var mail = new Mailpit(properties(), fake, null); var first = mail.open(); var second = mail.open()) {
            assertThat(first.recipient()).isNotEqualTo(second.recipient());
            fake.message("one", first.recipient()); fake.message("two", second.recipient());
            assertThat(first.awaitMessage().text()).contains("private-token");
            first.close();
            assertThat(fake.messages).containsKeys("foreign", "two").doesNotContainKey("one");
            assertThat(second.messages()).hasSize(1);
        }
        assertThat(fake.messages).containsOnlyKeys("foreign");
        assertThat(fake.deleted).containsExactlyInAnyOrder("one", "two");
    }
    @Test void foreignMessagesAndAttachmentsAreNeverReadOrDeletedAsOwned() {
        Fake fake = new Fake();
        try (var mail = new Mailpit(properties(), fake, null); var scope = mail.open()) {
            fake.message("owned", scope.recipient()); fake.message("foreign", "other@test.invalid");
            assertThatThrownBy(() -> scope.attachment("foreign", "1")).hasMessageContaining("foreign");
            assertThatThrownBy(() -> scope.attachment("owned", "2")).hasMessageContaining("does not belong");
            assertThat(fake.attachmentRequests).isZero();
            assertThat(scope.attachment("owned", "1")).containsExactly((byte)1);
        }
        assertThat(fake.messages).containsOnlyKeys("foreign");
    }
    @Test void failedCleanupCanBeRetriedAndContentDoesNotEnterTimeoutDiagnostics() {
        Fake fake = new Fake();
        var mail = new Mailpit(properties(), fake, null); var scope = mail.open();
        assertThatThrownBy(scope::awaitMessage).hasMessageNotContaining("private-token").hasMessageNotContaining("private-subject");
        fake.message("one", scope.recipient());
        assertThat(scope.awaitMessage().toString()).doesNotContain("private", "OTP");
        fake.failDelete = true;
        assertThatThrownBy(mail::close).hasMessageContaining("request failed");
        assertThatThrownBy(mail::open).hasMessageContaining("closed");
        fake.failDelete = false; mail.close(); mail.close();
        assertThat(fake.messages).isEmpty();
    }
    @Test void sharedMessagesAreRefusedAndEvidenceContainsNoContent(@org.junit.jupiter.api.io.TempDir java.nio.file.Path output) throws Exception {
        Fake fake = new Fake();
        var sink = new io.testforge.artifact.ArtifactSink() {
            public java.nio.file.Path directoryFor(String source) { return output; }
            public void register(io.testforge.artifact.TestArtifact artifact) { }
            public io.testforge.artifact.TestArtifact write(String a, String b, String c, String d, String e) { throw new AssertionError("Use collision-safe files"); }
        };
        try (var mail = new Mailpit(properties(), fake, sink)) {
            var first = mail.open(); String recipient = first.recipient();
            fake.message("one", recipient);
            var owned = fake.messages.get("one");
            fake.messages.put("one", new MailMessage("one", owned.to(), List.of("foreign@test.invalid"), List.of(),
                    owned.subject(), owned.text(), owned.html(), owned.attachments()));
            assertThatThrownBy(first::close).hasMessageContaining("foreign/shared");
            assertThat(fake.deleted).isEmpty();
            fake.messages.put("one", owned); first.close();
            try (var second = mail.open()) { fake.message("two", second.recipient()); }
            try (var files = java.nio.file.Files.list(output)) {
                var reports = files.toList(); assertThat(reports).hasSize(3);
                for (var report : reports) assertThat(java.nio.file.Files.readString(report))
                        .doesNotContain(recipient, "private-token", "private-subject", "private-file", "foreign@test.invalid");
            }
        }
    }

}
