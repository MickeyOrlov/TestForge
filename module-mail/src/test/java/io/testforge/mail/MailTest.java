package io.testforge.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MailTest {

    private MailProperties properties() {
        return new MailProperties(true, "http://localhost:8025", null, null,
                Duration.ofMillis(100), Duration.ofMillis(150), Duration.ofMillis(10), 0, 0);
    }

    static class Fake implements MailBackend {
        final Map<String, MailMessage> messages = new LinkedHashMap<>();
        final List<String> deleted = new ArrayList<>();
        boolean failDelete;
        boolean returnForeign;
        int attachmentRequests;

        @Override
        public List<String> search(String recipient, Duration budget) {
            return messages.values().stream().filter(m -> returnForeign || m.to().contains(recipient))
                    .map(MailMessage::id).toList();
        }

        @Override
        public MailMessage fetch(String id, Duration budget) {
            return messages.get(id);
        }

        @Override
        public byte[] attachment(String id, String partId, Duration budget) {
            attachmentRequests++;
            return new byte[] {1};
        }

        @Override
        public void delete(List<String> ids, Duration budget) {
            if (failDelete) {
                throw new IllegalStateException("Mailpit request failed");
            }
            deleted.addAll(ids);
            ids.forEach(messages::remove);
        }

        @Override
        public void close() {
        }

        void message(String id, String to) {
            messages.put(id, new MailMessage(id, List.of(to), List.of(), List.of(), "private-subject",
                    "OTP: private-token", "<b>private-token</b>",
                    List.of(new MailMessage.Attachment("1", "private-file", "text/plain", 1, "hash"))));
        }

        void shared(String id, String to) {
            message(id, to);
            MailMessage owned = messages.get(id);
            messages.put(id, new MailMessage(id, owned.to(), List.of("foreign@test.invalid"), List.of(),
                    owned.subject(), owned.text(), owned.html(), owned.attachments()));
        }
    }

    @Test
    void configurationIsInertAndMissingTargetFailsBeforeOpeningAScope() {
        var context = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgeMailAutoConfiguration.class));
        context.run(c -> assertThat(c).doesNotHaveBean(Mailpit.class));
        context.withPropertyValues("forge.mail.enabled=true").run(c -> {
            assertThat(c).hasSingleBean(Mailpit.class);
            assertThatThrownBy(() -> c.getBean(Mailpit.class).open()).hasMessageContaining("api-url");
        });
        for (String bad : List.of("http://user:secret@host", "https://host?token=secret", "file:///tmp/mail",
                "http://host/secret/../x")) {
            assertThatThrownBy(() -> new MailProperties(true, bad, null, null, null, null, null, 0, 0))
                    .hasMessageNotContaining("secret");
        }
    }

    @Test
    void aWebrootIsKeptAndBasicAuthNeedsHttpsOffLoopback() {
        var webroot = new MailProperties(true, "http://mail.test.invalid/mailpit", null, null,
                null, null, null, 0, 0);
        assertThat(webroot.apiBase().resolve("api/v1/messages").toString())
                .isEqualTo("http://mail.test.invalid/mailpit/api/v1/messages");
        assertThat(properties().apiBase().resolve("api/v1/messages").toString())
                .isEqualTo("http://localhost:8025/api/v1/messages");

        assertThatThrownBy(() -> new MailProperties(true, "http://mail.test.invalid", "ci", "pw",
                null, null, null, 0, 0)).hasMessageContaining("https");
        assertThat(new MailProperties(true, "https://mail.test.invalid", "ci", "pw", null, null, null, 0, 0))
                .isNotNull();
        assertThat(new MailProperties(true, "http://127.0.0.1:8025", "ci", "pw", null, null, null, 0, 0))
                .isNotNull();
    }

    @Test
    void scopesAreIsolatedAndNeverDeleteUnrelatedMail() {
        Fake fake = new Fake();
        fake.message("foreign", "someone@else.test");
        try (var mail = new Mailpit(properties(), fake, null); var first = mail.open(); var second = mail.open()) {
            assertThat(first.recipient()).isNotEqualTo(second.recipient());
            fake.message("one", first.recipient());
            fake.message("two", second.recipient());
            assertThat(first.awaitMessage().text()).contains("private-token");
            first.close();
            assertThat(fake.messages).containsKeys("foreign", "two").doesNotContainKey("one");
            assertThat(second.messages()).hasSize(1);
        }
        assertThat(fake.messages).containsOnlyKeys("foreign");
        assertThat(fake.deleted).containsExactlyInAnyOrder("one", "two");
    }

    @Test
    void awaitMessageReturnsTheNextMessageNotOneAlreadyReturned() {
        Fake fake = new Fake();
        try (var mail = new Mailpit(properties(), fake, null); var scope = mail.open()) {
            fake.message("one", scope.recipient());
            assertThat(scope.awaitMessage().id()).isEqualTo("one");
            assertThatThrownBy(scope::awaitMessage).as("no second message has arrived")
                    .hasMessageContaining("recipient-isolated");
            fake.message("two", scope.recipient());
            assertThat(scope.awaitMessage().id()).isEqualTo("two");
        }
    }

    @Test
    void aRefusalStopsTheWaitAtOnceInsteadOfTimingOut() {
        Fake fake = new Fake();
        var slow = new MailProperties(true, "http://localhost:8025", null, null,
                Duration.ofMillis(100), Duration.ofSeconds(30), Duration.ofMillis(10), 0, 0);
        try (var mail = new Mailpit(slow, fake, null)) {
            var scope = mail.open();
            fake.shared("one", scope.recipient());
            long started = System.nanoTime();
            assertThatThrownBy(scope::awaitMessage).isInstanceOf(MailRefusedException.class)
                    .hasMessageContaining("foreign/shared");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThatThrownBy(scope::close).isInstanceOf(MailRefusedException.class);
        }
    }

    @Test
    void foreignMessagesAndAttachmentsAreNeverReadOrDeletedAsOwned() {
        Fake fake = new Fake();
        try (var mail = new Mailpit(properties(), fake, null); var scope = mail.open()) {
            fake.message("owned", scope.recipient());
            fake.message("foreign", "other@test.invalid");
            assertThatThrownBy(() -> scope.attachment("foreign", "1")).hasMessageContaining("foreign");
            assertThatThrownBy(() -> scope.attachment("owned", "2")).hasMessageContaining("does not belong");
            assertThat(fake.attachmentRequests).isZero();
            assertThat(scope.attachment("owned", "1")).containsExactly((byte) 1);
        }
        assertThat(fake.messages).containsOnlyKeys("foreign");
    }

    @Test
    void failedCleanupCanBeRetriedAndContentDoesNotEnterTimeoutDiagnostics() {
        Fake fake = new Fake();
        var mail = new Mailpit(properties(), fake, null);
        var scope = mail.open();
        assertThatThrownBy(scope::awaitMessage).hasMessageNotContaining("private-token")
                .hasMessageNotContaining("private-subject");
        fake.message("one", scope.recipient());
        assertThat(scope.awaitMessage().toString()).doesNotContain("private", "OTP");
        fake.failDelete = true;
        assertThatThrownBy(mail::close).hasMessageContaining("request failed");
        assertThatThrownBy(mail::open).hasMessageContaining("closed");
        fake.failDelete = false;
        mail.close();
        mail.close();
        assertThat(fake.messages).isEmpty();
    }

    @Test
    void aSharedMessageIsLeftOnTheServerAndDoesNotBlockClosing(@TempDir Path output) throws Exception {
        Fake fake = new Fake();
        List<Path> files = new ArrayList<>();
        var sink = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                return output;
            }

            @Override
            public Path newFile(String source, String name) {
                Path file = output.resolve(name + "-" + files.size());
                files.add(file);
                return file;
            }

            @Override
            public void register(TestArtifact artifact) {
            }

            @Override
            public TestArtifact write(String a, String b, String c, String d, String e) {
                throw new AssertionError("Use collision-safe files");
            }
        };
        var mail = new Mailpit(properties(), fake, sink);
        var first = mail.open();
        String recipient = first.recipient();
        fake.message("mine", recipient);
        fake.shared("shared", recipient);

        assertThatThrownBy(first::close).isInstanceOf(MailRefusedException.class)
                .hasMessageContaining("1 shared message(s)");
        assertThat(fake.deleted).containsExactly("mine");
        assertThat(fake.messages).containsOnlyKeys("shared");
        first.close();

        fake.failDelete = true;
        var second = mail.open();
        fake.message("two", second.recipient());
        assertThatThrownBy(second::close).hasMessageContaining("request failed");
        fake.failDelete = false;
        mail.close();
        mail.close();
        assertThat(fake.messages).containsOnlyKeys("shared");

        assertThat(files).as("one report per scope, not per close attempt").hasSize(2);
        assertThat(Files.readString(files.get(0))).contains("\"sharedMessagesLeft\" : 1")
                .contains("\"cleanupSucceeded\" : false");
        assertThat(Files.readString(files.get(1))).contains("\"cleanupAttempts\" : 2")
                .contains("\"cleanupSucceeded\" : true");
        for (Path report : files) {
            assertThat(Files.readString(report)).doesNotContain(recipient, "private-token", "private-subject",
                    "private-file", "foreign@test.invalid");
        }
    }
}
