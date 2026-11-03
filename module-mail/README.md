# module-mail

Opt-in recipient scopes over [Mailpit](https://mailpit.axllent.org/), an MIT-licensed SMTP capture server. Mailpit owns SMTP, MIME parsing, search and storage. TestForge adds unique recipients, bounded API calls, scoped cleanup and value-free evidence. The reference fixture pins Mailpit 1.33.0; install/run the server separately. Production dependencies are core, Spring Boot auto-configuration and Jackson; no other TestForge module is required.

## Use

Configure the tested application's SMTP host/port to your **test-only** Mailpit server before startup. Disable Mailpit SMTP forwarding/release and use a dedicated instance with a retention policy. This adapter neither sends mail nor changes the application's SMTP configuration.

```yaml
forge:
  mail:
    enabled: true
    api-url: http://127.0.0.1:8025
    request-timeout: 3s
    await-timeout: 10s
    poll-interval: 100ms
    max-messages: 100
    max-response-bytes: 5242880
    # username: ${MAILPIT_API_USERNAME}
    # password: ${MAILPIT_API_PASSWORD}
```

```java
try (MailScope scope = mailpit.open()) {
    // Drive the product using this exact recipient, for example a signup form:
    signUp(scope.recipient());
    MailMessage message = scope.awaitMessage();
    assertThat(message.subject()).isEqualTo("Confirm your account");
    assertThat(message.text()).contains("verification");
    byte[] receipt = scope.attachment(message.id(), message.attachments().getFirst().partId());
    assertThat(receipt).isNotEmpty();
}
```

Opening a scope performs no network I/O. Each uses a UUID address at `testforge.invalid`; your test environment must allow it. Polling and cleanup require exactly one matching To recipient with no Cc/Bcc. Shared-recipient messages are refused, never deleted. `messages()` lists current owned messages; `awaitMessage()` waits for the first available message, not necessarily the newest. Use a new scope per scenario. Native Mailpit parses text, HTML, recipient lists and attachment metadata; OTP/link extraction and business assertions stay in project tests. Attachment content is fetched explicitly with ownership verification.

Calls have request and operation deadlines, response-size and message-count limits. API redirects are refused. The API origin must be explicit HTTP(S), without credentials, path, query or fragment; optional Basic authentication is supported (use HTTPS off loopback). Remote server errors are sanitized. Returned content is intentionally available to your assertions: do not log it or use real credentials/addresses. DTO `toString()` methods redact content, but assertion libraries can still print explicit accessor values.

## Cleanup and evidence

Closing discovers and verifies owned messages before issuing native DELETE with explicit nonempty IDs. No purge-all call exists. Cleanup failures propagate and can be retried; closing the parent also closes active scopes. A shutdown hook is best effort only. Stop the product's asynchronous senders before closing: a message delivered after cleanup needs server retention or later operator cleanup. This is isolation for cooperating tests, not access control against a malicious caller or shared server administrator.

When an ArtifactSink exists, each close attempt writes collision-safe `mail.json` with opaque scope ID, timestamps, counts and cleanup outcome. No recipient, subject, body, URL, token, credential, message ID or attachment bytes enter evidence. `deletedMessages` counts IDs acknowledged by a successful delete request. Native server/UI logs and retention remain operator-owned. There is no module-quality dependency or score.

```bash
./gradlew :module-mail:test :architecture-tests:test
./gradlew :example-tests:mailTest  # explicit Docker/SMTP/API fixture
./gradlew build                  # no SMTP or Mailpit traffic
```

The native fixture binds an owned Mailpit container to loopback, sends MIME through Spring's JavaMailSender, checks text/HTML/attachment bytes and verifies recipient isolation during cleanup. The starter-mail dependency belongs to the example only.
