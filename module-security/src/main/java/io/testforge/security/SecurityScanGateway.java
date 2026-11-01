package io.testforge.security;

import com.sun.net.httpserver.*;
import io.testforge.core.wait.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/** Loopback reverse gateway: the scanner never receives the real target or credentials. */
final class SecurityScanGateway implements AutoCloseable {
  record Route(String method, String template, Pattern path) {}

  record Credential(String headerName, String headerValue, String queryName, String queryValue) {
    static Credential anonymous() {
      return new Credential(null, null, null, null);
    }

    @Override
    public String toString() {
      return "Credential[redacted]";
    }
  }

  // with "expect", every header java.net.http.HttpClient refuses to set: forwarding one would
  // throw and mark the whole scan incomplete
  private static final Set<String> DROP =
      Set.of(
          "host",
          "content-length",
          "connection",
          "expect",
          "transfer-encoding",
          "upgrade",
          "proxy-authorization",
          "authorization",
          "cookie",
          "x-api-key",
          "x-http-method-override",
          "x-method-override",
          "x-http-method");
  private final HttpServer server;
  private final ExecutorService workers;
  private final URI target;
  private final SecurityActiveProperties p;
  private final List<Route> routes;
  private final java.util.function.Supplier<Credential> credentials;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  final Set<String> succeeded = ConcurrentHashMap.newKeySet();
  final Set<String> visited = ConcurrentHashMap.newKeySet();
  final Set<String> excludedKinds = ConcurrentHashMap.newKeySet();
  final Set<String> refusalKinds = ConcurrentHashMap.newKeySet();
  final AtomicBoolean incomplete = new AtomicBoolean();
  final AtomicInteger requests = new AtomicInteger();
  private final String prefix = "/security-scope-" + UUID.randomUUID();
  private final long deadline;
  private long next;

  SecurityScanGateway(
      URI target,
      SecurityActiveProperties p,
      List<Route> routes,
      java.util.function.Supplier<Credential> credentials)
      throws IOException {
    this.target = target;
    this.p = p;
    this.routes = List.copyOf(routes);
    this.credentials = credentials;
    deadline = System.nanoTime() + p.timeBudget().toNanos();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    workers =
        Executors.newFixedThreadPool(
            2,
            task -> {
              Thread t = new Thread(task, "security-scan-gateway");
              t.setDaemon(true);
              return t;
            });
    server.setExecutor(workers);
    server.createContext("/", this::forward);
    server.start();
  }

  String localBase() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + prefix;
  }

  String scannerBase() {
    return "http://" + DockerZapEngine.gatewayHost() + ":" + server.getAddress().getPort() + prefix;
  }

  private void forward(HttpExchange exchange) throws IOException {
    try {
      String raw = exchange.getRequestURI().getRawPath();
      String decoded = exchange.getRequestURI().getPath();
      if (!decoded.startsWith(prefix + "/") || !raw.startsWith(prefix + "/")) {
        exclude(exchange, "outside-prefix");
        return;
      }
      if (decoded.contains("..") || decoded.contains("\\") || decoded.contains("%")) {
        refusalKinds.add("UNSUPPORTED_PATH");
        refuse(exchange, 403);
        return;
      }
      String path = decoded.substring(prefix.length()), method = exchange.getRequestMethod();
      Route route =
          routes.stream()
              .filter(
                  r ->
                      (r.method().equals(method)
                              || method.equals("HEAD") && r.method().equals("GET"))
                          && r.path().matcher(path).matches())
              .findFirst()
              .orElse(null);
      if (route == null || !Set.of("GET", "HEAD", "OPTIONS").contains(method)) {
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(method)) {
          refusalKinds.add("UNSAFE_METHOD");
          refuse(exchange, 403);
        } else exclude(exchange, "undeclared-read-route");
        return;
      }
      String query = exchange.getRequestURI().getRawQuery();
      if (query != null)
        for (String pair : query.split("&")) {
          String key =
              URLDecoder.decode(pair.split("=", 2)[0], java.nio.charset.StandardCharsets.UTF_8)
                  .toLowerCase(Locale.ROOT);
          if (Set.of("_method", "method", "httpmethod", "x-http-method-override").contains(key)) {
            refuse(exchange, 403);
            return;
          }
        }
      if (!reserve()) {
        refuse(exchange, 429);
        return;
      }
      Credential credential = credentials.get();
      if (credential.queryName() != null) {
        var filtered = new ArrayList<String>();
        if (query != null)
          for (String pair : query.split("&")) {
            String key =
                URLDecoder.decode(pair.split("=", 2)[0], java.nio.charset.StandardCharsets.UTF_8);
            if (!key.equals(credential.queryName())) filtered.add(pair);
          }
        filtered.add(
            URLEncoder.encode(credential.queryName(), java.nio.charset.StandardCharsets.UTF_8)
                + "="
                + URLEncoder.encode(
                    credential.queryValue(), java.nio.charset.StandardCharsets.UTF_8));
        query = String.join("&", filtered);
      }
      String upstream =
          target.toString().replaceAll("/+$", "")
              + raw.substring(prefix.length())
              + (query == null ? "" : "?" + query);
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(upstream))
              .timeout(Duration.ofSeconds(10))
              .method(method, HttpRequest.BodyPublishers.noBody());
      for (var header : exchange.getRequestHeaders().entrySet())
        if (!DROP.contains(header.getKey().toLowerCase(Locale.ROOT))
            && (credential.headerName() == null
                || !header.getKey().equalsIgnoreCase(credential.headerName())))
          for (String value : header.getValue()) request.header(header.getKey(), value);
      if (credential.headerName() != null)
        request.header(credential.headerName(), credential.headerValue());
      var response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      byte[] body;
      try (var input = response.body()) {
        body = input.readNBytes(p.maxResponseBytes() + 1);
      }
      if (body.length > p.maxResponseBytes()) {
        refuse(exchange, 502);
        return;
      }
      String key = route.method() + " " + route.template();
      visited.add(key);
      if (response.statusCode() >= 200 && response.statusCode() < 300) succeeded.add(key);
      for (var header : response.headers().map().entrySet())
        if (!DROP.contains(header.getKey().toLowerCase(Locale.ROOT))
            && !header.getKey().equalsIgnoreCase("location"))
          exchange.getResponseHeaders().put(header.getKey(), header.getValue());
      if (response.headers().firstValue("Location").isPresent()) {
        URI next =
            URI.create(upstream).resolve(response.headers().firstValue("Location").orElseThrow());
        String basePath = target.getPath() == null ? "" : target.getPath().replaceAll("/+$", "");
        String relative =
            next.getPath().startsWith(basePath) ? next.getPath().substring(basePath.length()) : "";
        if (!sameOrigin(target, next)
            || routes.stream().noneMatch(r -> r.path().matcher(relative).matches())) {
          refuse(exchange, 403);
          return;
        }
        exchange
            .getResponseHeaders()
            .set(
                "Location",
                scannerBase()
                    + relative
                    + (next.getRawQuery() == null ? "" : "?" + next.getRawQuery()));
      }
      int status = response.statusCode();
      exchange.sendResponseHeaders(
          status, method.equals("HEAD") || status == 204 || status == 304 ? -1 : body.length);
      if (!method.equals("HEAD") && status != 204 && status != 304)
        exchange.getResponseBody().write(body);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      incomplete.set(true);
      refuse(exchange, 502);
    } catch (Exception e) {
      refusalKinds.add(
          e instanceof IllegalArgumentException ? "UNSUPPORTED_REQUEST" : "UPSTREAM_FAILURE");
      incomplete.set(true);
      refuse(exchange, 502);
    } finally {
      exchange.close();
    }
  }

  private synchronized boolean reserve() {
    if (System.nanoTime() >= deadline || requests.incrementAndGet() > p.maxRequests()) return false;
    long ready = next;
    new Waiter(new WaitProperties(Duration.ofSeconds(2), Duration.ofMillis(10)))
        .await(
            "security scan rate budget", () -> System.nanoTime() >= ready, Boolean::booleanValue);
    next = System.nanoTime() + 1_000_000_000L / p.maxRequestsPerSecond();
    return System.nanoTime() < deadline;
  }

  private void exclude(HttpExchange exchange, String reason) throws IOException {
    excludedKinds.add(reason);
    exchange.getResponseHeaders().clear();
    exchange.sendResponseHeaders(403, -1);
  }

  private void refuse(HttpExchange exchange, int status) throws IOException {
    refusalKinds.add(
        status == 429 ? "BUDGET" : status == 403 ? "SCOPE" : "TRANSPORT_OR_BODY_LIMIT");
    incomplete.set(true);
    exchange.getResponseHeaders().clear();
    exchange.sendResponseHeaders(status, -1);
  }

  private static boolean sameOrigin(URI a, URI b) {
    return Objects.equals(a.getScheme(), b.getScheme())
        && Objects.equals(a.getHost(), b.getHost())
        && port(a) == port(b);
  }

  private static int port(URI u) {
    return u.getPort() < 0 ? (u.getScheme().equals("https") ? 443 : 80) : u.getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    workers.shutdownNow();
  }
}
