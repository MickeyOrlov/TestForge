package io.testforge.load;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** A real child JVM for process lifecycle tests; no external CLI or network is required. */
public final class ProcessFixture {
    private ProcessFixture() {
    }

    public static void main(String[] args) throws Exception {
        try (var output = new FileOutputStream(FileDescriptor.out)) {
            if (args[0].equals("noise")) {
                output.write("x".repeat(250_000).getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (args[0].equals("env")) {
                output.write((System.getenv("LOAD_TOKEN") + ":" + System.getenv("USER") + ":"
                        + System.getenv("K6_OUT")).getBytes(StandardCharsets.UTF_8));
                return;
            }
            Files.writeString(Path.of(args[1]), String.valueOf(ProcessHandle.current().pid()));
            if (args[0].equals("supervisor")) {
                new LocalLoadProcess().run(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), ProcessFixture.class.getName(), "wait", args[2]),
                        Path.of(args[1]).getParent(), Map.of(), Duration.ofSeconds(30));
                return;
            }
            if (args[0].equals("parent")) {
                new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), ProcessFixture.class.getName(),
                        "wait", args[2]).start();
            }
            new CountDownLatch(1).await();
        }
    }
}
