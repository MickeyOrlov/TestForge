package io.testforge.security;

import java.net.URI;
import java.util.List;

/** Exact hosts (optional port), HTTP(S), no userinfo or implicit redirect approval. */
public final class SecurityTargetPolicy {
  private final List<String> hosts;

  public SecurityTargetPolicy(List<String> hosts) {
    this.hosts = List.copyOf(hosts);
  }

  public URI require(String url) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("security target is not a valid URI");
    }
    String host = uri.getHost();
    if (!List.of("http", "https").contains(uri.getScheme())
        || host == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || hosts.stream()
            .noneMatch(
                h ->
                    h.equalsIgnoreCase(host)
                        || h.equalsIgnoreCase(host + ":" + effectivePort(uri))))
      throw new IllegalArgumentException("security target is not explicitly allowlisted");
    return uri;
  }

  private static int effectivePort(URI u) {
    return u.getPort() < 0 ? (u.getScheme().equals("https") ? 443 : 80) : u.getPort();
  }

  public static void requireRelative(String path) {
    if (path == null
        || !path.startsWith("/")
        || path.startsWith("//")
        || path.contains("\\")
        || path.contains("?")
        || path.contains("#")
        || path.contains(".."))
      throw new IllegalArgumentException(
          "security probe needs an absolute-path template without query, traversal or authority");
  }
}
