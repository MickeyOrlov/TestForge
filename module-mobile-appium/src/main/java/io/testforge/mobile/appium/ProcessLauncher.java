package io.testforge.mobile.appium;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface ProcessLauncher {

    Process launch(List<String> command) throws IOException;

    /**
     * Launches with the process's output (stdout and stderr) written to
     * {@code log}. A launcher that cannot redirect falls back to
     * {@link #launch(List)}.
     */
    default Process launch(List<String> command, Path log) throws IOException {
        return launch(command);
    }
}
