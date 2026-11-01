package io.testforge.security;

import java.net.*;
import java.net.http.*;
import java.security.cert.X509Certificate;
import java.time.*;
import java.util.*;
import javax.net.ssl.*;

/**
 * Bounded JDK TLS/redirect probes. Client-disabled algorithms are unmeasured, not proof of server
 * rejection.
 */
public class SecurityTransportRunner {
  private final SecurityTransportProperties properties;
  private final SecurityCollector collector;
  private final SSLContext ssl;
  private final Clock clock;

  public SecurityTransportRunner(SecurityTransportProperties p, SecurityCollector c) {
    this(p, c, defaultContext(), Clock.systemUTC());
  }

  public SecurityTransportRunner(
      SecurityTransportProperties p, SecurityCollector c, SSLContext ssl, Clock clock) {
    this.properties = p;
    this.collector = c;
    this.ssl = ssl;
    this.clock = clock;
  }

  private static SSLContext defaultContext() {
    try {
      return SSLContext.getDefault();
    } catch (Exception e) {
      throw new IllegalStateException("default TLS context unavailable");
    }
  }

  public void check(String url) {
    if (!properties.enabled())
      throw new IllegalStateException("forge.security.transport.enabled must be true");
    URI target = new SecurityTargetPolicy(properties.allowedHosts()).require(url);
    if (target.getQuery() != null)
      throw new IllegalArgumentException("transport targets must not contain query credentials");
    String label =
        target.getScheme()
            + "://"
            + target.getHost()
            + ":"
            + (target.getPort() < 0
                ? (target.getScheme().equals("https") ? 443 : 80)
                : target.getPort());
    if (target.getScheme().equals("http")) {
      redirect(target, label);
      return;
    }
    SSLSession session;
    try (var socket = connect(target, null, null)) {
      session = socket.getSession();
      var certificate = (X509Certificate) session.getPeerCertificates()[0];
      try {
        certificate.checkValidity(Date.from(clock.instant()));
        collector.covered("tls-validation", label, null, "measured", null);
      } catch (java.security.cert.CertificateException e) {
        finding(
            "tls-validation",
            label,
            Severity.HIGH,
            "certificate is not valid at the assessment time");
      }
      if (certificate
          .getNotAfter()
          .toInstant()
          .isBefore(clock.instant().plus(Duration.ofDays(properties.expiryWarningDays()))))
        finding(
            "tls-expiry-window",
            label,
            Severity.MEDIUM,
            "certificate expires within the configured warning window");
      collector.covered("tls-expiry-window", label, null, "measured", null);
      collector.covered("tls-negotiated-cipher", label, null, "measured", null);
      if (weak(session.getCipherSuite()))
        finding(
            "tls-negotiated-cipher",
            label,
            Severity.HIGH,
            "connection negotiated a weak cipher suite");
    } catch (javax.net.ssl.SSLHandshakeException e) {
      finding(
          "tls-validation",
          label,
          Severity.HIGH,
          "certificate, hostname or TLS handshake validation failed");
      return;
    } catch (Exception e) {
      collector.covered(
          "tls-validation", label, null, "not_measured", "TLS connection could not be established");
      return;
    }
    for (String protocol : List.of("TLSv1", "TLSv1.1")) {
      String check = "tls-legacy-" + protocol;
      if (!properties.probeLegacy()) {
        collector.covered(
            check,
            label,
            null,
            "not_applicable",
            "legacy probing explicitly disabled; no claim about server support");
        continue;
      }
      if (!Arrays.asList(ssl.getDefaultSSLParameters().getProtocols()).contains(protocol)) {
        collector.covered(
            check,
            label,
            null,
            "not_measured",
            "local JDK disables the legacy protocol; server support not determined");
        continue;
      }
      try (var socket = connect(target, new String[] {protocol}, null)) {
        finding(check, label, Severity.HIGH, "server accepted a legacy TLS protocol");
      } catch (SSLHandshakeException e) {
        collector.covered(
            check, label, null, "measured", "server rejected locally available legacy protocol");
      } catch (Exception e) {
        collector.covered(
            check, label, null, "not_measured", "legacy protocol probe did not complete");
      }
    }
    List<String> weak =
        Arrays.stream(ssl.getDefaultSSLParameters().getCipherSuites())
            .filter(SecurityTransportRunner::weak)
            .limit(16)
            .toList();
    if (!properties.probeWeakCiphers())
      collector.covered(
          "tls-weak-ciphers",
          label,
          null,
          "not_applicable",
          "weak-cipher probing explicitly disabled; no server-wide cipher claim");
    else if (weak.isEmpty())
      collector.covered(
          "tls-weak-ciphers",
          label,
          null,
          "not_measured",
          "local JDK enables no weak cipher suites; server support not determined");
    else
      for (String cipher : weak)
        try (var socket = connect(target, null, new String[] {cipher})) {
          finding("tls-weak-ciphers", label, Severity.HIGH, "server accepted a weak cipher suite");
        } catch (SSLHandshakeException e) {
          collector.covered(
              "tls-weak-ciphers",
              label,
              null,
              "measured",
              "server rejected locally available weak cipher");
        } catch (Exception e) {
          collector.covered(
              "tls-weak-ciphers", label, null, "not_measured", "cipher probe did not complete");
        }
  }

  private SSLSocket connect(URI uri, String[] protocols, String[] ciphers) throws Exception {
    SSLSocket socket = (SSLSocket) ssl.getSocketFactory().createSocket();
    try {
      int timeout = Math.toIntExact(properties.timeout().toMillis());
      socket.connect(
          new InetSocketAddress(uri.getHost(), uri.getPort() < 0 ? 443 : uri.getPort()), timeout);
      socket.setSoTimeout(timeout);
      var parameters = socket.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      if (protocols != null) parameters.setProtocols(protocols);
      if (ciphers != null) parameters.setCipherSuites(ciphers);
      socket.setSSLParameters(parameters);
      socket.startHandshake();
      return socket;
    } catch (Exception e) {
      socket.close();
      throw e;
    }
  }

  private void redirect(URI target, String label) {
    try {
      var client =
          HttpClient.newBuilder()
              .connectTimeout(properties.timeout())
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
      var response =
          client.send(
              HttpRequest.newBuilder(target)
                  .timeout(properties.timeout())
                  .method("HEAD", HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      boolean upgrade = Set.of(301, 302, 307, 308).contains(response.statusCode());
      URI location = target.resolve(response.headers().firstValue("Location").orElse(""));
      upgrade =
          upgrade
              && "https".equals(location.getScheme())
              && target.getHost().equalsIgnoreCase(location.getHost());
      collector.covered("https-redirect", label, null, "measured", null);
      if (!upgrade)
        finding(
            "https-redirect",
            label,
            Severity.MEDIUM,
            "HTTP endpoint does not redirect to HTTPS on the same host");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      collector.covered(
          "https-redirect", label, null, "not_measured", "redirect probe interrupted");
    } catch (Exception e) {
      collector.covered("https-redirect", label, null, "not_measured", "redirect probe failed");
    }
  }

  private static boolean weak(String cipher) {
    return cipher.matches(".*(_NULL_|_anon_|_EXPORT_|_RC4_|_DES_|3DES|_MD5).*");
  }

  private void finding(String check, String target, Severity severity, String evidence) {
    collector.covered(check, target, null, "measured", null);
    collector.finding(check, severity, target, evidence, "API8:2023");
  }
}
