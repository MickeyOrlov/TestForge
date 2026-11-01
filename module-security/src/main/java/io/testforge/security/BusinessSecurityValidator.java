package io.testforge.security;

import io.testforge.api.explorer.PreparedRequest;
import io.testforge.security.BusinessSecuritySuite.Kind;
import io.testforge.security.BusinessSecuritySuite.Phase;
import io.testforge.security.BusinessSecuritySuite.Step;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Preflight the whole suite before controls, mutations or cleanup can send traffic. */
final class BusinessSecurityValidator {
    private final BusinessSecurityProperties properties;
    private final java.util.function.Predicate<String> knownRole;
    private final java.util.function.Consumer<PreparedRequest> requestValidator;

    BusinessSecurityValidator(
            BusinessSecurityProperties properties, BusinessSecurityExchange exchange) {
        this(properties, exchange::knownRole, exchange::validate);
    }

    BusinessSecurityValidator(
            BusinessSecurityProperties properties,
            java.util.function.Predicate<String> knownRole,
            java.util.function.Consumer<PreparedRequest> requestValidator) {
        this.properties = properties;
        this.knownRole = knownRole;
        this.requestValidator = requestValidator;
    }

    void validateCase(
            BusinessSecuritySuite suite,
            BusinessSecuritySuite.Case test,
            Set<String> operations,
            Set<String> inputs) {
        if (test.steps().size() + test.cleanup().size() > properties.maxSteps())
            throw new BusinessSecurityException("business case exceeds max-steps");
        boolean control = false, probe = false;
        List<Step> ownerControls = new ArrayList<>();
        PreparedRequest lastMutation = null;
        PreparedRequest primary = null;
        List<PreparedRequest> replayControls = new ArrayList<>();
        if (Set.of(Kind.PROPERTY_WRITE, Kind.TRANSITION, Kind.REPLAY).contains(test.kind())
                && Set.of("GET", "HEAD", "OPTIONS").contains(test.method()))
            throw new BusinessSecurityException(
                    "write/transition/replay cases require a write operation");
        int lastWrite = -1, lastVerify = -1, lastProbe = -1, index = 0;
        Set<String> captured = new HashSet<>(inputs);
        Set<String> ids = new HashSet<>();
        List<Step> all = new ArrayList<>(test.steps());
        all.addAll(test.cleanup());
        int captureCount = 0;
        for (var step : all) {
            captureCount += step.captures().size();
            if (captureCount > properties.maxSteps())
                throw new BusinessSecurityException("too many business captures");
            if (!ids.add(step.id()) || !knownRole.test(step.role()))
                throw new BusinessSecurityException("duplicate step or unknown role");
            requestValidator.accept(step.request());
            if (!operations.contains(
                    step.request().method().toUpperCase(Locale.ROOT)
                            + " "
                            + step.request().pathTemplate()))
                throw new BusinessSecurityException("business step is outside document scope");
            for (String capture : step.captures().keySet())
                if (!captured.add(capture))
                    throw new BusinessSecurityException(
                            "business captures must not overwrite bindings");
            if (test.cleanup().contains(step)) {
                if (step.phase() != Phase.CONTROL)
                    throw new BusinessSecurityException("cleanup steps must be controls");
                continue;
            }
            index++;
            if (!Set.of("GET", "HEAD", "OPTIONS")
                            .contains(step.request().method().toUpperCase(Locale.ROOT))
                    && (step.phase() == Phase.PROBE || probe)) {
                lastWrite = index;
                lastMutation = step.request();
            }
            if (step.phase() == Phase.CONTROL && !probe) {
                control = true;
                ownerControls.add(step);
                if (step.role().equals(test.role())) replayControls.add(step.request());
            }
            if (step.phase() == Phase.PROBE) {
                if (!control)
                    throw new BusinessSecurityException(
                            "business probe needs a preceding positive control");
                if (step.role().equals(test.role())
                        && step.request().method().equalsIgnoreCase(test.method())
                        && step.request().pathTemplate().equals(test.path())) {
                    boolean foreignControl =
                            ownerControls.stream()
                                    .anyMatch(
                                            owner ->
                                                    !owner.role().equals(test.role())
                                                            && sameFixture(
                                                                    owner.request(),
                                                                    step.request()));
                    boolean tenantControl =
                            ownerControls.stream()
                                    .anyMatch(
                                            owner ->
                                                    !owner.role().equals(test.role())
                                                            && sameFixture(
                                                                    owner.request(), step.request())
                                                            && suite.tenants().get(test.role())
                                                                    != null
                                                            && suite.tenants().get(owner.role())
                                                                    != null
                                                            && !suite.tenants()
                                                                    .get(test.role())
                                                                    .equals(
                                                                            suite.tenants()
                                                                                    .get(
                                                                                            owner
                                                                                                    .role())));
                    if ((test.kind() == Kind.OBJECT_ACCESS || test.kind() == Kind.TENANT_ACCESS)
                            && !foreignControl)
                        throw new BusinessSecurityException(
                                "object access needs a positive control under a distinct owner"
                                        + " role");
                    if (test.kind() == Kind.TENANT_ACCESS && !tenantControl)
                        throw new BusinessSecurityException(
                                "tenant access needs explicit distinct tenant aliases for owner and"
                                        + " actor");
                    if (test.kind() == Kind.REPLAY && !replayControls.contains(step.request()))
                        throw new BusinessSecurityException(
                                "replay needs a preceding identical successful request under the"
                                        + " same role");
                    if (test.kind() == Kind.PROPERTY_WRITE && step.request().body() == null)
                        throw new BusinessSecurityException(
                                "property-write probe requires an explicit body");
                    probe = true;
                    primary = step.request();
                    lastProbe = index;
                }
            }
            if (step.phase() == Phase.VERIFY
                    && probe
                    && !step.assertions().isEmpty()
                    && sameFixture(step.request(), lastMutation == null ? primary : lastMutation))
                lastVerify = index;
        }
        if (!probe || !control)
            throw new BusinessSecurityException("case needs control and matching probe");
        boolean writes =
                test.steps().stream()
                        .anyMatch(
                                step ->
                                        !Set.of("GET", "HEAD", "OPTIONS")
                                                .contains(
                                                        step.request()
                                                                .method()
                                                                .toUpperCase(Locale.ROOT)));
        if (writes && lastVerify <= Math.max(lastWrite, lastProbe))
            throw new BusinessSecurityException(
                    "business writes need subsequent persisted-state assertions");
        if (test.kind() == Kind.PROPERTY_READ
                && test.steps().stream()
                        .noneMatch(
                                step ->
                                        step.phase() == Phase.PROBE
                                                && step.role().equals(test.role())
                                                && step.request()
                                                        .method()
                                                        .equalsIgnoreCase(test.method())
                                                && step.request().pathTemplate().equals(test.path())
                                                && step.assertions().stream()
                                                        .anyMatch(
                                                                a ->
                                                                        a.match()
                                                                                == BusinessSecuritySuite
                                                                                        .Match
                                                                                        .ABSENT)))
            throw new BusinessSecurityException(
                    "property-read case needs an absent-field probe assertion");
    }

    private static boolean sameFixture(PreparedRequest read, PreparedRequest operation) {
        String parent = operation.pathTemplate().replaceFirst("/[^/]+$", "");
        return read.method().equalsIgnoreCase("GET")
                && (read.pathTemplate().equals(operation.pathTemplate())
                        || read.pathTemplate().equals(parent))
                && read.pathParameters().equals(operation.pathParameters())
                && read.queryParameters().equals(operation.queryParameters());
    }
}
