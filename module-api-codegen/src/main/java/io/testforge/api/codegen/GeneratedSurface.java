package io.testforge.api.codegen;

import io.testforge.api.discovery.ApiChange;
import io.testforge.api.discovery.ApiChange.Impact;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What tests compile against in one spec's generated sources: every client
 * with its methods, every record with its components. Written as
 * {@code surface.json} next to the sources, so the next generation can say
 * what it changed for the tests that use them.
 *
 * @param types by simple type name
 */
public record GeneratedSurface(Map<String, TypeSurface> types) {

    public GeneratedSurface {
        types = types == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(types));
    }

    /**
     * @param kind {@code client} or {@code record}
     * @param constructed a record a test builds itself — a request body or
     *        something inside one — so a new component breaks its {@code new}
     *        calls; response-only records are read, never built
     * @param members method name to {@code (int bookingId, String expand)}, or
     *        component name to its type, in declaration order
     */
    public record TypeSurface(String kind, boolean constructed, Map<String, String> members) {

        public TypeSurface {
            members = members == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(members));
        }
    }

    /**
     * Every difference from {@code before}, judged for a test compiled against
     * it: {@code BREAKING} stops it compiling, {@code RISKY} compiles but may
     * now pass different values, {@code SAFE} is new surface nothing used yet.
     * Worst first.
     */
    public List<ApiChange> changesSince(GeneratedSurface before) {
        List<ApiChange> changes = new ArrayList<>();
        before.types().forEach((name, old) -> {
            if (!types.containsKey(name)) {
                changes.add(new ApiChange(name, Impact.BREAKING,
                        old.kind() + " removed: tests that use it no longer compile"));
            }
        });
        types.forEach((name, now) -> {
            TypeSurface old = before.types().get(name);
            if (old == null) {
                changes.add(new ApiChange(name, Impact.SAFE, now.kind() + " added"));
            } else {
                compareMembers(name, old, now, changes);
            }
        });
        changes.sort(Comparator.comparing(ApiChange::impact).thenComparing(ApiChange::location));
        return List.copyOf(changes);
    }

    private static void compareMembers(String type, TypeSurface old, TypeSurface now, List<ApiChange> changes) {
        boolean client = "client".equals(now.kind());
        old.members().forEach((member, signature) -> {
            if (!now.members().containsKey(member)) {
                changes.add(new ApiChange(type + "." + member, Impact.BREAKING,
                        (client ? "method" : "component") + " removed: tests that use it no longer compile"));
            }
        });
        now.members().forEach((member, signature) -> {
            String location = type + "." + member;
            String was = old.members().get(member);
            if (was == null) {
                if (client) {
                    changes.add(new ApiChange(location, Impact.SAFE, "method added " + signature));
                } else if (now.constructed()) {
                    changes.add(new ApiChange(location, Impact.BREAKING, "component added " + signature
                            + ": the record is built by tests, and every new " + type
                            + "(...) call needs one more argument"));
                } else {
                    changes.add(new ApiChange(location, Impact.SAFE, "component added " + signature));
                }
            } else if (!was.equals(signature)) {
                if (client && parameterTypes(was).equals(parameterTypes(signature))) {
                    changes.add(new ApiChange(location, Impact.RISKY, "parameters renamed or reordered "
                            + was + " -> " + signature + ": calls still compile and may now pass values to "
                            + "the wrong parameter"));
                } else {
                    changes.add(new ApiChange(location, Impact.BREAKING, (client ? "signature" : "type")
                            + " changed " + was + " -> " + signature));
                }
            }
        });
    }

    /** {@code (int bookingId, String expand)} → {@code [int, String]}; generic arguments kept whole. */
    static List<String> parameterTypes(String signature) {
        String inside = signature.substring(signature.indexOf('(') + 1, signature.lastIndexOf(')')).strip();
        List<String> types = new ArrayList<>();
        if (inside.isEmpty()) {
            return types;
        }
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : inside.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                types.add(typeOf(current.toString()));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        types.add(typeOf(current.toString()));
        return types;
    }

    private static String typeOf(String parameter) {
        String trimmed = parameter.strip();
        int space = trimmed.lastIndexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space).strip();
    }
}
