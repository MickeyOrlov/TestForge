package io.testforge.mobile.appium;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class HttpAppiumStatusProbe implements AppiumStatusProbe {

    private final HttpClient client = HttpClient.newHttpClient();

    @Override
    public boolean isReady(URI hubUrl, String statusPath) {
        try {
            HttpRequest request = HttpRequest.newBuilder(statusUri(hubUrl, statusPath))
                    .GET()
                    .build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private URI statusUri(URI hubUrl, String statusPath) {
        String path = statusPath == null || statusPath.isBlank() ? "/status" : statusPath;
        return hubUrl.resolve(path);
    }
}
