package io.testforge.security;

import java.net.URI;

/**
 * Restricts reported paths; credentials, query strings and identifier-like segments are not
 * retained.
 */
public final class SecurityTargets {
  private SecurityTargets() {}

  public static String endpoint(String method, String uri) {
    String path;
    try {
      path = URI.create(uri).getPath();
    } catch (RuntimeException e) {
      path = "/{unresolved}";
    }
    if (path == null || path.isBlank()) path = "/";
    StringBuilder safe = new StringBuilder();
    for (String part : path.split("/")) {
      if (part.isEmpty()) continue;
      safe.append('/').append(part.matches("[a-zA-Z][a-zA-Z_-]{0,39}") ? part : "{value}");
    }
    return method.toUpperCase(java.util.Locale.ROOT)
        + " "
        + (safe.isEmpty() ? "/" : safe.toString());
  }

  static String field(String name) {
    return name.matches("[a-zA-Z_][a-zA-Z0-9_-]{0,39}") ? name : "{field}";
  }
}
