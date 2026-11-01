package io.testforge.security;

import io.testforge.api.explorer.PreparedRequest;
import io.testforge.auth.AuthContext;
import io.testforge.auth.AuthProperties;
import io.testforge.auth.CredentialPlacement;
import io.testforge.auth.TokenProvider;
import io.testforge.http.HttpProperties;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Private bounded HTTP transport: role placement reused, defaults/filters/logging never inherited.
 */
final class BusinessSecurityExchange {
    private final HttpProperties http;
    private final TokenProvider tokens;
    private final AuthProperties auth;
    private final BusinessSecurityProperties properties;
    private final SecuritySecretRegistry secrets;
    private final HttpClient client;

    BusinessSecurityExchange(
            HttpProperties http,
            TokenProvider tokens,
            AuthProperties auth,
            BusinessSecurityProperties properties,
            SecuritySecretRegistry secrets) {
        this.http = http;
        this.tokens = tokens;
        this.auth = auth;
        this.properties = properties;
        this.secrets = secrets;
        client =
                HttpClient.newBuilder()
                        .connectTimeout(http.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    boolean knownRole(String role) {
        return AuthContext.ANONYMOUS.equals(role)
                || tokens != null && tokens.roles().contains(role);
    }

    void validate(PreparedRequest request) {
        URI base =
                new SecurityTargetPolicy(properties.allowedHosts())
                        .require(http.resolveBaseUrl(null));
        if (base.getRawQuery() != null)
            throw new BusinessSecurityException("business base URL must not contain query data");
        BusinessSecuritySuite.method(request.method());
        SecurityTargetPolicy.requireRelative(request.pathTemplate());
        if (request.pathTemplate().contains("/./") || request.pathTemplate().endsWith("/."))
            throw new BusinessSecurityException("dot path segments are not supported");
        if (request.pathTemplate().matches("(?i).*%(2e|2f|5c|25).*"))
            throw new BusinessSecurityException("encoded structural paths are not supported");
        if (!List.of("GET", "HEAD", "OPTIONS")
                        .contains(request.method().toUpperCase(java.util.Locale.ROOT))
                && !(properties.writeChecks() && properties.allowUnsafeMethods()))
            throw new BusinessSecurityException(
                    "business writes require write-checks and allow-unsafe-methods");
        for (String name : request.queryParameters().keySet()) {
            if (name.contains("%")
                    || Set.of(
                                    "_method",
                                    "method",
                                    "httpmethod",
                                    "x-http-method-override",
                                    "x-method-override",
                                    "x-http-method")
                            .contains(name.toLowerCase(java.util.Locale.ROOT)))
                throw new BusinessSecurityException(
                        "business method-override query parameters are forbidden");
        }
        if (List.of("GET", "HEAD", "OPTIONS")
                        .contains(request.method().toUpperCase(java.util.Locale.ROOT))
                && request.body() != null)
            throw new BusinessSecurityException("business read operations must not carry bodies");
        if (request.body() != null
                && (request.contentType() != null
                                && !request.contentType().equals("application/json")
                        || request.body().getBytes(StandardCharsets.UTF_8).length
                                > properties.maxBodyBytes()))
            throw new BusinessSecurityException("business bodies require bounded JSON");
    }

    HttpResponse<byte[]> send(PreparedRequest request, String role, Duration remaining)
            throws Exception {
        long started = System.nanoTime();
        validate(request);
        URI base = URI.create(http.resolveBaseUrl(null));
        String path = request.pathTemplate();
        for (var entry : request.pathParameters().entrySet()) {
            String value = entry.getValue();
            if (value.isEmpty()
                    || value.equals(".")
                    || value.contains("..")
                    || value.contains("/")
                    || value.contains("\\")
                    || value.contains("%"))
                throw new BusinessSecurityException(
                        "business path binding must be a single safe segment");
            path = path.replace("{" + entry.getKey() + "}", encode(value));
        }
        if (path.contains("{") || path.contains("}"))
            throw new BusinessSecurityException("missing path binding");
        Map<String, String> query = new TreeMap<>(request.queryParameters());
        if (tokens != null)
            for (String name : tokens.roles())
                tokens.placement(name)
                        .filter(p -> p.kind() == CredentialPlacement.Kind.QUERY)
                        .ifPresent(p -> query.remove(p.name()));
        String credential = null;
        CredentialPlacement placement = null;
        if (!AuthContext.ANONYMOUS.equals(role)) {
            credential = tokens.forRole(role);
            secrets.register(credential);
            int equals = credential.indexOf('=');
            if (equals >= 0) secrets.register(credential.substring(equals + 1));
            placement =
                    tokens.placement(role)
                            .orElseGet(
                                    () -> CredentialPlacement.header(auth.header(), auth.scheme()));
            if (placement.kind() == CredentialPlacement.Kind.QUERY)
                query.put(placement.name(), credential);
        }
        List<String> pairs = new ArrayList<>();
        query.forEach((key, value) -> pairs.add(encode(key) + "=" + encode(value)));
        String root = base.toString().replaceAll("/+$", "");
        URI uri = URI.create(root + path + (pairs.isEmpty() ? "" : "?" + String.join("&", pairs)));
        new SecurityTargetPolicy(properties.allowedHosts()).require(uri.toString());
        remaining = remaining.minusNanos(System.nanoTime() - started);
        if (remaining.isNegative() || remaining.isZero())
            throw new BusinessSecurityException("business request budget exhausted before send");
        Duration timeout =
                remaining.compareTo(http.readTimeout()) < 0 ? remaining : http.readTimeout();
        var builder =
                HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json");
        if (placement != null)
            switch (placement.kind()) {
                case HEADER -> builder.header(placement.name(), placement.prefix() + credential);
                case COOKIE -> builder.header("Cookie", credential);
                case QUERY -> {}
            }
        if (request.body() != null) builder.header("Content-Type", "application/json");
        var pending =
                client.sendAsync(
                        builder.method(
                                        request.method().toUpperCase(java.util.Locale.ROOT),
                                        request.body() == null
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofString(
                                                        request.body()))
                                .build(),
                        info -> new BoundedBody(properties.maxBodyBytes()));
        try {
            return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException | TimeoutException e) {
            pending.cancel(true);
            throw e;
        }
    }

    void close() {
        client.shutdownNow();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;

        BoundedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(
                            new IllegalStateException("business response limit reached"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(new IllegalStateException("business response failed"));
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
