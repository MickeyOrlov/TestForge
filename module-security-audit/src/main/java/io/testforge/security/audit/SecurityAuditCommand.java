package io.testforge.security.audit;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Private structured command. Never put credentials in executable/arguments or serialize this. */
public final class SecurityAuditCommand implements AutoCloseable {
    private final Path executable;
    private final List<String> arguments;
    private final byte[] input;
    private final Map<String, String> targetAliases;
    private final java.util.Set<Integer> successExitCodes;

    public SecurityAuditCommand(
            Path executable,
            List<String> arguments,
            byte[] input,
            Map<String, String> targetAliases) {
        this(executable, arguments, input, targetAliases, java.util.Set.of(0));
    }

    public SecurityAuditCommand(
            Path executable,
            List<String> arguments,
            byte[] input,
            Map<String, String> targetAliases,
            java.util.Set<Integer> successExitCodes) {
        this.successExitCodes = java.util.Set.copyOf(successExitCodes);
        if (this.successExitCodes.isEmpty()
                || this.successExitCodes.size() > 8
                || this.successExitCodes.stream().anyMatch(v -> v < 0 || v > 255))
            throw SecurityAuditProfile.invalid("audit adapter exit-code policy is invalid");
        if (executable == null
                || !executable.isAbsolute()
                || !executable.normalize().equals(executable))
            throw SecurityAuditProfile.invalid(
                    "audit adapter needs an absolute normalized executable");
        this.executable = executable;
        this.arguments = List.copyOf(arguments == null ? List.of() : arguments);
        if (this.arguments.size() > 256
                || this.arguments.stream()
                        .anyMatch(
                                a ->
                                        a.length() > 4096
                                                || a.chars().anyMatch(Character::isISOControl))
                || this.arguments.stream().mapToLong(String::length).sum() > 32768
                || input != null && input.length > 1048576
                || targetAliases != null && targetAliases.size() > 10000)
            throw SecurityAuditProfile.invalid("audit command exceeds argument or input bounds");
        this.input = input == null ? new byte[0] : input.clone();
        this.targetAliases = Map.copyOf(targetAliases == null ? Map.of() : targetAliases);
        this.targetAliases.values().forEach(SecurityAuditProfile::alias);
    }

    public Path executable() {
        return executable;
    }

    public List<String> arguments() {
        return arguments;
    }

    public synchronized byte[] input() {
        return input.clone();
    }

    public java.util.Set<Integer> successExitCodes() {
        return successExitCodes;
    }

    public Map<String, String> targetAliases() {
        return targetAliases;
    }

    @Override
    public synchronized void close() {
        Arrays.fill(input, (byte) 0);
    }

    @Override
    public String toString() {
        return "SecurityAuditCommand[redacted]";
    }
}
