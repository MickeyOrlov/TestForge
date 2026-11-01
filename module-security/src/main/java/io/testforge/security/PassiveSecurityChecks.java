package io.testforge.security;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Stateless rules over an already-received response. No network, raw-value evidence or inferred
 * business policy.
 */
public class PassiveSecurityChecks {
  private static final String MISCONFIG = "API8:2023";
  private static final Pattern JWT =
      Pattern.compile(
          "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
  private static final Pattern CARD =
      Pattern.compile("(?<![0-9])(?:[0-9][ -]?){12,18}[0-9](?![0-9])");
  private static final Pattern TRACE =
      Pattern.compile(
          "(?is)(Exception in thread|\\bat [a-zA-Z_$][\\w.$]+\\([^)]*\\.java:\\d+\\)|Traceback"
              + " \\(most recent call"
              + " last\\)|SQLSTATE\\[|org\\.postgresql\\.util\\.PSQLException)");
  private static final Pattern PRIVATE_IP =
      Pattern.compile(
          "\\b(?:10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}|172\\.(?:1[6-9]|2[0-9]|3[01])\\.\\d{1,3}\\.\\d{1,3})\\b");
  private static final Pattern KEY =
      Pattern.compile(
          "(?i)(?:api[_-]?key|access[_-]?token|password|secret)\\s*[\"'=:\\s]+[A-Za-z0-9_+/=-]{12,}");
  private final SecurityProperties.Passive properties;
  private final SecurityCollector collector;
  private final ObjectMapper mapper = new ObjectMapper();

  public PassiveSecurityChecks(SecurityProperties.Passive properties, SecurityCollector collector) {
    this.properties = properties;
    this.collector = collector;
  }

  public void inspect(
      String method,
      String uri,
      boolean authenticated,
      String origin,
      int status,
      Map<String, List<String>> responseHeaders,
      String body) {
    collector.observedBlock("passive");
    String target = SecurityTargets.endpoint(method, uri);
    Map<String, List<String>> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    h.putAll(responseHeaders);
    boolean https = uri.toLowerCase(Locale.ROOT).startsWith("https:");
    boolean html = first(h, "Content-Type").toLowerCase(Locale.ROOT).contains("text/html");
    rule(
        "missing-hsts",
        target,
        https,
        !first(h, "Strict-Transport-Security").isBlank(),
        Severity.MEDIUM,
        "HTTPS response is missing HSTS");
    rule(
        "missing-nosniff",
        target,
        true,
        first(h, "X-Content-Type-Options").equalsIgnoreCase("nosniff"),
        Severity.LOW,
        "response does not declare nosniff");
    rule(
        "missing-csp",
        target,
        html,
        !first(h, "Content-Security-Policy").isBlank(),
        Severity.MEDIUM,
        "HTML response is missing CSP");
    rule(
        "missing-frame-protection",
        target,
        html,
        Set.of("DENY", "SAMEORIGIN").contains(first(h, "X-Frame-Options").toUpperCase(Locale.ROOT))
            || first(h, "Content-Security-Policy")
                .toLowerCase(Locale.ROOT)
                .contains("frame-ancestors"),
        Severity.MEDIUM,
        "HTML response lacks frame protection");
    rule(
        "authenticated-cache",
        target,
        authenticated,
        first(h, "Cache-Control").toLowerCase(Locale.ROOT).matches(".*\\bno-store\\b.*"),
        Severity.LOW,
        "authenticated response does not declare no-store");
    boolean corsCredentials = first(h, "Access-Control-Allow-Credentials").equalsIgnoreCase("true");
    rule(
        "cors-credentials-wildcard",
        target,
        true,
        !(corsCredentials && first(h, "Access-Control-Allow-Origin").equals("*")),
        Severity.MEDIUM,
        "wildcard origin combined with credentials");
    boolean untrusted = origin != null && properties.untrustedOrigins().contains(origin);
    rule(
        "cors-untrusted-reflection",
        target,
        untrusted,
        !Objects.equals(origin, first(h, "Access-Control-Allow-Origin")),
        Severity.HIGH,
        "configured untrusted request origin was permitted");
    for (String cookie : h.getOrDefault("Set-Cookie", List.of())) cookie(target, cookie);
    boolean version =
        java.util.stream.Stream.of("Server", "X-Powered-By", "X-AspNet-Version")
            .anyMatch(name -> first(h, name).matches(".*[0-9]+\\.[0-9]+.*"));
    rule(
        "version-disclosure",
        target,
        true,
        !version,
        Severity.LOW,
        "response header discloses a software version");
    String text = body == null ? "" : body;
    boolean truncated = text.length() > properties.maxBodyChars();
    if (truncated) {
      text = text.substring(0, properties.maxBodyChars());
      collector.covered(
          "response-body",
          target,
          null,
          "not_measured",
          "body exceeds scan limit; only prefix inspected");
    }
    rule(
        "error-disclosure",
        target,
        status >= 400,
        !TRACE.matcher(text).find(),
        Severity.MEDIUM,
        "error body contains a stack-trace or SQL-error pattern");
    rule(
        "internal-address",
        target,
        status >= 400,
        !PRIVATE_IP.matcher(text).find(),
        Severity.LOW,
        "error body contains a private-address pattern");
    boolean allowed = properties.allowedSensitivePaths().contains(target);
    if (!allowed) {
      rule(
          "token-exposure",
          target,
          true,
          !JWT.matcher(text).find() && !KEY.matcher(text).find(),
          Severity.HIGH,
          "response body contains a credential-like pattern");
      var cards = CARD.matcher(text);
      boolean card = false;
      while (cards.find())
        if (luhn(cards.group().replaceAll("[^0-9]", ""))) {
          card = true;
          break;
        }
      rule(
          "card-exposure",
          target,
          true,
          !card,
          Severity.HIGH,
          "response body contains a Luhn-valid card-number pattern");
      try {
        JsonNode json = mapper.readTree(text);
        if (json != null) fields(json, "$", target);
      } catch (Exception e) {
        if (!properties.forbiddenFields().isEmpty())
          collector.covered(
              "forbidden-fields", target, null, "not_measured", "response is not complete JSON");
      }
    } else
      collector.covered(
          "sensitive-response",
          target,
          null,
          "not_applicable",
          "project explicitly permits sensitive content at this endpoint");
  }

  private void fields(JsonNode node, String path, String target) {
    if (node.isObject())
      node.fields()
          .forEachRemaining(
              entry -> {
                String next = path + "." + SecurityTargets.field(entry.getKey());
                if (properties.forbiddenFields().contains(next) && !entry.getValue().isNull())
                  collector.finding(
                      "forbidden-field",
                      Severity.HIGH,
                      target + " " + next,
                      "project-forbidden field is present",
                      "API3:2023");
                fields(entry.getValue(), next, target);
              });
    else if (node.isArray()) node.forEach(child -> fields(child, path + "[*]", target));
    collector.covered("forbidden-fields", target, null, "measured", null);
  }

  private void cookie(String target, String cookie) {
    String[] parts = cookie.split(";");
    int eq = parts[0].indexOf('=');
    if (eq < 1) return;
    String name = parts[0].substring(0, eq).trim();
    if (properties.sessionCookies().stream().noneMatch(n -> n.equalsIgnoreCase(name))) return;
    Set<String> attributes = new HashSet<>();
    for (int i = 1; i < parts.length; i++) attributes.add(parts[i].trim().toLowerCase(Locale.ROOT));
    String location = target + " cookie " + SecurityTargets.field(name);
    rule(
        "cookie-secure",
        location,
        true,
        attributes.contains("secure"),
        Severity.MEDIUM,
        "session cookie is missing Secure");
    rule(
        "cookie-httponly",
        location,
        true,
        attributes.contains("httponly"),
        Severity.MEDIUM,
        "session cookie is missing HttpOnly");
    rule(
        "cookie-samesite",
        location,
        true,
        attributes.stream().anyMatch(a -> a.matches("samesite=(strict|lax|none)")),
        Severity.LOW,
        "session cookie has no valid SameSite attribute");
    rule(
        "cookie-none-without-secure",
        location,
        true,
        !attributes.contains("samesite=none") || attributes.contains("secure"),
        Severity.MEDIUM,
        "SameSite=None cookie lacks Secure");
  }

  private static String first(Map<String, List<String>> h, String key) {
    return h.getOrDefault(key, List.of("")).stream().findFirst().orElse("");
  }

  private void rule(
      String check,
      String target,
      boolean applicable,
      boolean passed,
      Severity severity,
      String evidence) {
    collector.covered(
        check,
        target,
        null,
        applicable ? "measured" : "not_applicable",
        applicable ? null : "response context does not require this rule");
    if (applicable && !passed) collector.finding(check, severity, target, evidence, MISCONFIG);
  }

  static boolean luhn(String value) {
    if (value.length() < 13 || value.length() > 19 || value.chars().distinct().count() == 1)
      return false;
    int sum = 0;
    boolean twice = false;
    for (int i = value.length() - 1; i >= 0; i--) {
      int n = value.charAt(i) - '0';
      if (twice) {
        n *= 2;
        if (n > 9) n -= 9;
      }
      sum += n;
      twice = !twice;
    }
    return sum % 10 == 0;
  }
}
