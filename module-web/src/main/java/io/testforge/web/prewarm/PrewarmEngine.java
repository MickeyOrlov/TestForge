package io.testforge.web.prewarm;

/** How pages are visited; the browser engine is the default. */
public interface PrewarmEngine {

    /** Starts whatever visiting needs (a browser, a client); may throw when it cannot. */
    Session open(PrewarmProperties properties);

    /** One prewarm's visits, sharing whatever {@link #open} started. */
    interface Session extends AutoCloseable {

        /** Visits one page; reports a failure in the result instead of throwing. */
        PrewarmVisit visit(String url);

        @Override
        void close();
    }
}
