package io.testforge.mail;

import java.time.Duration;
import java.util.List;

/** Native Mailpit REST operations; fake implementations keep unit tests offline. */
public interface MailBackend extends AutoCloseable {
    List<String> search(String recipient, Duration budget);
    MailMessage fetch(String id, Duration budget);
    byte[] attachment(String id, String partId, Duration budget);
    void delete(List<String> ids, Duration budget);
    @Override void close();
}
