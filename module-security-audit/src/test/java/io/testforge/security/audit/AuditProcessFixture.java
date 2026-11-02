package io.testforge.security.audit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/** Owned subprocess fixture: no network or scanner dependencies. */
public class AuditProcessFixture {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Files.writeString(
                Path.of(mode.equals("CHILD_WAIT") ? "child.pid" : "started.pid"),
                Long.toString(ProcessHandle.current().pid()));
        if (mode.equals("HANG") || mode.equals("CHILD_WAIT")) {
            new CountDownLatch(1).await();
            return;
        }
        if (mode.equals("CHILDREN")) {
            var children = new java.util.ArrayList<Process>();
            for (int i = 0; i < 3; i++)
                children.add(
                        new ProcessBuilder(
                                        Path.of(System.getProperty("java.home"), "bin", "java")
                                                .toString(),
                                        "-cp",
                                        System.getProperty("java.class.path"),
                                        AuditProcessFixture.class.getName(),
                                        "CHILD_WAIT")
                                .inheritIO()
                                .start());
            for (var child : children) child.waitFor();
            return;
        }
        if (mode.equals("CHILD")) {
            Process child =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    AuditProcessFixture.class.getName(),
                                    "CHILD_WAIT")
                            .inheritIO()
                            .start();
            child.waitFor();
            return;
        }
        byte[] input = System.in.readAllBytes();
        if (mode.equals("FLOOD_OUT") || mode.equals("FLOOD_ERR")) {
            var output = mode.equals("FLOOD_OUT") ? System.out : System.err;
            while (true) {
                output.write(new byte[4096]);
                output.flush();
            }
        }
        if (mode.equals("SECRET")) {
            System.out.write(input);
            return;
        }
        if (mode.equals("BAD")) {
            System.out.print("{broken");
            return;
        }
        if (!Set.of("LANG", "LC_ALL", "HOME", "TMPDIR", "__CF_USER_TEXT_ENCODING")
                .containsAll(System.getenv().keySet()))
            throw new IllegalStateException("inherited environment");
        if (!Path.of("").toRealPath().equals(Path.of(System.getenv("HOME")))
                || !System.getenv("HOME").equals(System.getenv("TMPDIR")))
            throw new IllegalStateException("workspace mismatch");
        Files.writeString(Path.of("scratch.tmp"), "synthetic-private-scratch");
        if (mode.equals("STDERR")) System.err.write(input);
        boolean finding = mode.equals("VULN");
        String findings =
                finding
                        ? "{\"id\":\"fixture-rule GET"
                              + " /fixture\",\"check\":\"fixture-rule\",\"severity\":\"high\",\"target\":\"GET"
                              + " /fixture\",\"evidence\":\"fixture"
                              + " finding\",\"owasp\":\"API2:2023\",\"status\":\"new\",\"reason\":null}"
                        : "";
        String report =
                "{\"formatVersion\":1,\"enabled\":true,\"measured\":true,\"complete\":true,\"healthy\":"
                        + !finding
                        + ",\"findings\":["
                        + findings
                        + "],\"coverage\":[{\"check\":\"fixture-rule\",\"target\":\"GET"
                        + " /fixture\",\"role\":null,\"status\":\"measured\",\"reason\":null}],\"newCounts\":{\"critical\":0,\"high\":"
                        + (finding ? 1 : 0)
                        + ",\"medium\":0,\"low\":0,\"info\":0}}";
        System.out.write(report.getBytes(StandardCharsets.UTF_8));
        if (mode.equals("VULN")) System.exit(2);
        if (mode.equals("NONZERO")) System.exit(7);
    }
}
