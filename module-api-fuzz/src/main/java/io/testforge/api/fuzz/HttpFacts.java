package io.testforge.api.fuzz;

/**
 * The few status-code distinctions this module is entitled to make.
 *
 * <p>Kept in one place because getting them wrong is how a fuzzer starts
 * reporting fiction. A {@code 401} is not a schema rejecting input; neither is
 * a {@code 403}, a {@code 429}, or a redirect to a login page. Treating any of
 * them as "the service validated my input and refused it" turns an
 * authentication problem into a page of green validation results.
 */
final class HttpFacts {

    private HttpFacts() {
    }

    /** 2xx. */
    static boolean success(int status) {
        return status >= 200 && status < 300;
    }

    /**
     * Statuses that say something happened <em>before</em> the payload was
     * looked at: authentication, authorization, rate limiting, and redirects.
     * A response like this is evidence about the environment, never about
     * validation.
     */
    static boolean infrastructure(int status) {
        return status == 401 || status == 403 || status == 407 || status == 429
                || (status >= 300 && status < 400);
    }

    /**
     * The two statuses that actually mean "your payload was examined and
     * refused". Deliberately narrow: a {@code 404} means the resource is not
     * there, a {@code 409} means the state is wrong, a {@code 405} means the
     * route does not take this method. None of those is a verdict on the value
     * this module changed.
     */
    static boolean validationShaped(int status) {
        return status == 400 || status == 422;
    }

    static boolean serverError(int status) {
        return status >= 500;
    }
}
