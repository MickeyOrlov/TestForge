package io.testforge.web.prewarm;

/**
 * What one prewarm visit found.
 *
 * @param url      the configured URL, redacted for reports
 * @param status   the HTTP status of the page, or {@code null} when none was received
 * @param finalUrl where the visit ended after redirects, redacted; {@code null} when it did not load
 * @param millis   how long the visit took
 * @param error    why the visit failed, or {@code null}
 */
public record PrewarmVisit(String url, Integer status, String finalUrl, long millis, String error) {

    /** The environment answered: no error and no 5xx. A 4xx is an answer (a login wall, a missing page). */
    public boolean reached() {
        return error == null && (status == null || status < 500);
    }

    /** It answered somewhere else than asked — typically a redirect to a sign-in page. */
    public boolean redirected() {
        return finalUrl != null && !finalUrl.equals(url);
    }

    /** {@code https://staging.example.test/ -> 200 in 812 ms}. */
    public String describe() {
        StringBuilder out = new StringBuilder(url).append(" -> ");
        if (error != null) {
            out.append("failed: ").append(error);
        } else {
            out.append(status == null ? "loaded" : String.valueOf(status));
            if (redirected()) {
                out.append(" at ").append(finalUrl);
            }
        }
        return out.append(" in ").append(millis).append(" ms").toString();
    }
}
