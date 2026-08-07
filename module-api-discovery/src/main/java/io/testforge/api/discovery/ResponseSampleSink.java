package io.testforge.api.discovery;

import java.nio.file.Path;

/**
 * Receives every response the run decided to snapshot — a status in
 * {@code probe.snapshot-statuses}, a JSON content type, no transport error.
 *
 * <p>This is the seam the value-profile stage attaches to. Stage 1 derives the
 * shape map inside the runner; a later {@code ValueProfileSink} bean can count
 * observations, presence rates and enum candidates from the same samples
 * without the runner, the probe policy or the artifact layout changing at all.
 *
 * <p>Implementations must not retain the sample body beyond the call. It holds
 * real payload data, which is precisely what the rest of the module is built to
 * never write down.
 */
public interface ResponseSampleSink {

    void accept(EndpointDescriptor endpoint, ResponseSample sample);

    /** Called once after the last endpoint, for sinks that write their own artifact. */
    default void finish(Path outputDir) {
    }
}
