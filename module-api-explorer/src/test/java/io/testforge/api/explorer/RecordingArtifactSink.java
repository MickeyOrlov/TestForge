package io.testforge.api.explorer;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Collects what a producer registers, nothing more. */
final class RecordingArtifactSink implements ArtifactSink {

    final List<TestArtifact> registered = new CopyOnWriteArrayList<>();

    @Override
    public Path directoryFor(String source) {
        return Path.of("unused");
    }

    @Override
    public void register(TestArtifact artifact) {
        registered.add(artifact);
    }

    @Override
    public TestArtifact write(String source, String category, String name, String mediaType, String content) {
        throw new UnsupportedOperationException("producers under test only register files they wrote");
    }
}
