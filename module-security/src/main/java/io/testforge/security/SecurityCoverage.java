package io.testforge.security;

/** measured / not_measured / not_applicable, independent of the presence of findings. */
public record SecurityCoverage(
    String check, String target, String role, String status, String reason) {}
