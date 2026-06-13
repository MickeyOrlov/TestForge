package io.testforge.mobile.appium;

import java.net.URI;

public interface AppiumStatusProbe {

    boolean isReady(URI hubUrl, String statusPath);
}
