package io.testforge.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Bounded HTTP transport and typed native Mailpit v1 data. No SMTP server or MIME parser. */
public final class MailpitBackend implements MailBackend {
    private final MailProperties properties;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    public MailpitBackend(MailProperties properties) {
        this.properties = properties;
        http = HttpClient.newBuilder().connectTimeout(properties.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    static String id(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_.-]{1,200}") || id.contains("..") || id.equals("latest"))
            throw new IllegalArgumentException("Use an explicit Mailpit message/part ID");
        return id;
    }
    private byte[] request(String path, String method, byte[] body, Duration budget) {
        properties.validateRun();
        Duration timeout = budget.compareTo(properties.requestTimeout()) < 0 ? budget : properties.requestTimeout();
        if (timeout.toMillis() < 1) throw new IllegalStateException("Mail operation deadline expired");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.apiUrl()).resolve(path))
                .timeout(timeout).header("Accept", "application/json");
        if (properties.username() != null) builder.header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((properties.username() + ":" + properties.password()).getBytes(StandardCharsets.UTF_8)));
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        var pending = http.sendAsync(builder.build(), ignored -> new LimitedBody(properties.maxResponseBytes()));
        try {
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new IOException();
            return response.body();
        } catch (InterruptedException e) {
            pending.cancel(true); Thread.currentThread().interrupt();
            throw new IllegalStateException("Mailpit operation interrupted");
        } catch (Exception e) {
            pending.cancel(true);
            // HTTP errors, response data and origin credentials never enter test diagnostics.
            throw new IllegalStateException("Mailpit request failed or exceeded its deadline/response limit");
        }
    }
    private JsonNode json(String path, Duration budget) {
        try {
            JsonNode value = mapper.readTree(request(path, "GET", null, budget));
            if (value == null || !value.isObject()) throw new IOException();
            return value;
        } catch (IOException e) { throw new IllegalStateException("Invalid Mailpit JSON response"); }
    }
    @Override public List<String> search(String recipient, Duration budget) {
        if (!recipient.matches("tf-[a-f0-9-]{36}@testforge[.]invalid")) throw new IllegalArgumentException("Use a scope recipient");
        long started = System.nanoTime();
        List<String> ids = new ArrayList<>();
        int start = 0;
        do {
            Duration left = budget.minusNanos(System.nanoTime() - started);
            var response = json("/api/v1/search?query=" + URLEncoder.encode("to:" + recipient, StandardCharsets.UTF_8)
                    + "&start=" + start + "&limit=50", left);
            if (!response.path("messages").isArray() || !response.path("messages_count").isIntegralNumber() || !response.path("messages_count").canConvertToInt())
                throw new IllegalStateException("Unknown Mailpit search response");
            int count = response.path("messages_count").intValue();
            if (count < start + response.path("messages").size()) throw new IllegalStateException("Inconsistent Mailpit result count");
            if (count < 0 || count > properties.maxMessages()) throw new IllegalStateException("Mail scope message limit exceeded");
            for (JsonNode message : response.path("messages")) ids.add(id(message.path("ID").asText()));
            start += response.path("messages").size();
            if (ids.size() > properties.maxMessages()) throw new IllegalStateException("Mail scope message limit exceeded");
            if (start >= count) break;
            if (response.path("messages").isEmpty()) throw new IllegalStateException("Incomplete Mailpit search pagination");
        } while (true);
        return ids.stream().distinct().toList();
    }
    private List<String> addresses(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalStateException("Unknown Mailpit recipient layout");
        List<String> addresses = new ArrayList<>();
        for (var address : node) {
            if (!address.path("Address").isTextual()) throw new IllegalStateException("Unknown Mailpit address layout");
            addresses.add(address.path("Address").textValue());
        }
        return List.copyOf(addresses);
    }
    @Override public MailMessage fetch(String messageId, Duration budget) {
        JsonNode message = json("/api/v1/message/" + id(messageId), budget);
        if (!messageId.equals(message.path("ID").asText()) || !message.path("Subject").isTextual()
                || !message.path("Text").isTextual() || !message.path("HTML").isTextual())
            throw new IllegalStateException("Unknown Mailpit message response");
        List<MailMessage.Attachment> attachments = new ArrayList<>();
        for (String type : List.of("Attachments", "Inline")) {
            JsonNode values = message.path(type);
            if (!values.isArray() && !values.isNull() && !values.isMissingNode()) throw new IllegalStateException("Unknown attachment layout");
            for (JsonNode part : values) attachments.add(new MailMessage.Attachment(id(part.path("PartID").asText()),
                    part.path("FileName").asText(), part.path("ContentType").asText(), part.path("Size").asLong(),
                    part.path("Checksums").path("SHA256").asText()));
        }
        return new MailMessage(messageId, addresses(message.path("To")), addresses(message.path("Cc")), addresses(message.path("Bcc")),
                message.path("Subject").textValue(), message.path("Text").textValue(), message.path("HTML").textValue(), attachments);
    }
    @Override public byte[] attachment(String messageId, String partId, Duration budget) {
        return request("/api/v1/message/" + id(messageId) + "/part/" + id(partId), "GET", null, budget);
    }
    @Override public void delete(List<String> ids, Duration budget) {
        if (ids == null || ids.isEmpty() || ids.size() > properties.maxMessages()) throw new IllegalArgumentException("Refusing unscoped mail deletion");
        try {
            request("/api/v1/messages", "DELETE", mapper.writeValueAsBytes(java.util.Map.of("IDs", ids.stream().map(MailpitBackend::id).toList())), budget);
        } catch (IOException e) { throw new IllegalStateException("Could not encode scoped message IDs"); }
    }
    @Override public void close() { http.close(); }

    /** Delegate HTTP decoding to the JDK; cancel before its byte-array subscriber can grow beyond the cap. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final long limit;
        private long size;
        private Flow.Subscription subscription;
        private boolean failed;
        LimitedBody(long limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (ByteBuffer buffer : buffers) size += buffer.remaining();
            if (size > limit) { failed = true; subscription.cancel(); delegate.onError(new IOException("Mail response limit")); }
            else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable throwable) { if (!failed) delegate.onError(throwable); }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
