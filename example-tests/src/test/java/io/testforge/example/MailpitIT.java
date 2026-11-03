package io.testforge.example;

import static org.assertj.core.api.Assertions.*;
import io.testforge.mail.*;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.testcontainers.containers.GenericContainer;
import java.util.List;

/** The application sends ordinary SMTP through Spring Mail; Mailpit captures and parses MIME. */
@Tag("containers")
class MailpitIT {
    private void send(JavaMailSenderImpl sender, String recipient, String token) throws Exception {
        MimeMessage message = sender.createMimeMessage();
        var helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom("service@testforge.invalid"); helper.setTo(recipient); helper.setSubject("Reset password");
        helper.setText("One-time token: " + token, "<p>One-time token: " + token + "</p>");
        helper.addAttachment("receipt.txt", new ByteArrayResource("fixture-receipt".getBytes(StandardCharsets.UTF_8)), "text/plain");
        sender.send(message);
    }
    @Test void smtpCaptureAndScopedCleanupPreserveAnotherScenariosMail() throws Exception {
        try (var server = new GenericContainer<>(System.getProperty("testforge.mailpit-image")).withExposedPorts(8025, 1025)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIp("127.0.0.1"),
                            new com.github.dockerjava.api.model.ExposedPort(8025)),
                    new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIp("127.0.0.1"),
                            new com.github.dockerjava.api.model.ExposedPort(1025))))) {
            server.start();
            var properties = new MailProperties(true, "http://" + server.getHost() + ":" + server.getMappedPort(8025),
                    null, null, null, null, null, 0, 0);
            var sender = new JavaMailSenderImpl(); sender.setHost(server.getHost()); sender.setPort(server.getMappedPort(1025));
            try (var mail = new Mailpit(properties, new MailpitBackend(properties), null);
                 var first = mail.open(); var second = mail.open()) {
                send(sender, first.recipient(), "first-private-otp"); send(sender, second.recipient(), "second-private-otp");
                var message = first.awaitMessage();
                assertThat(message.to()).containsExactly(first.recipient());
                assertThat(message.subject()).isEqualTo("Reset password");
                assertThat(message.text()).contains("first-private-otp").doesNotContain("second-private-otp");
                assertThat(message.attachments()).hasSize(1);
                assertThat(message.attachments().getFirst().fileName()).isEqualTo("receipt.txt");
                assertThat(first.attachment(message.id(), message.attachments().getFirst().partId()))
                        .isEqualTo("fixture-receipt".getBytes(StandardCharsets.UTF_8));
                first.close();
                assertThat(second.awaitMessage().text()).contains("second-private-otp");
            }
            // A new scope does not see messages from either completed scenario.
            try (var mail = new Mailpit(properties, new MailpitBackend(properties), null); var clean = mail.open()) {
                assertThat(clean.messages()).isEmpty();
            }
        }
    }
}
