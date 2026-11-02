package io.testforge.example;

/** Owned process fixture; it does not make network calls or implement a scanner. */
public class AuditExampleProcess {
    public static void main(String[] args) throws Exception {
        System.in.readAllBytes();
        System.out.print(
                """
{"formatVersion":1,"enabled":true,"measured":true,"complete":true,"healthy":true,
"findings":[],"coverage":[{"check":"fixture","target":"GET /fixture","role":null,"status":"measured","reason":null}],
"newCounts":{"critical":0,"high":0,"medium":0,"low":0,"info":0}}
""");
    }
}
