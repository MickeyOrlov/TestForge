package io.testforge.mobile.appium;

import java.io.IOException;
import java.util.List;

public class DefaultProcessLauncher implements ProcessLauncher {

    @Override
    public Process launch(List<String> command) throws IOException {
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
    }
}
