package io.testforge.mobile.appium;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class DefaultProcessLauncher implements ProcessLauncher {

    /**
     * Without a log file the output stays in a pipe nobody reads — so prefer
     * {@link #launch(List, Path)}: once an unread pipe's buffer fills, the
     * process blocks on its next log line.
     */
    @Override
    public Process launch(List<String> command) throws IOException {
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    @Override
    public Process launch(List<String> command, Path log) throws IOException {
        Path parent = log.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }
}
