package io.testforge.mobile.appium;

import java.io.IOException;
import java.util.List;

public interface ProcessLauncher {

    Process launch(List<String> command) throws IOException;
}
