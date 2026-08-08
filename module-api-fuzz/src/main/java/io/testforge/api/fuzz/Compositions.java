package io.testforge.api.fuzz;

import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The mutation policy for {@code oneOf} and {@code anyOf}.
 *
 * <p>A composition breaks the one assumption every {@code REJECT} rests on. The
 * baseline has to pick a branch to be buildable, and a value that violates the
 * chosen branch may be perfectly valid under a sibling — so the document has not
 * been broken at all, and a service that accepts it is behaving correctly. Report
 * that as {@code OVER_PERMISSIVE} and the finding is not merely wrong, it is
 * wrong in the direction that costs a team an afternoon.
 *
 * <p>So the rule is: mutate inside a composition only when the branch can be
 * <em>proven</em> to be the only one in play. A {@code discriminator} does that,
 * but only when the discriminating property is pinned to a single value in the
 * chosen branch and every sibling provably excludes that value. Then a request
 * carrying it can match one branch and no other, and a violation of that branch
 * is a violation of the schema.
 *
 * <p>Everything else is reported as unsupported with the reason. There is no
 * third option: guessing would put the module back in the business of
 * manufacturing findings it cannot defend.
 */
final class Compositions {

    private final UnaryOperator<Schema<?>> effective;

    Compositions(UnaryOperator<Schema<?>> effective) {
        this.effective = effective;
    }

    /**
     * The branch to build from, and whether anything inside it may be mutated.
     *
     * <p>{@code discriminatorPath} is the one place inside a pinned branch that
     * still may not be touched: changing the discriminator selects a different
     * branch, so the mutant would be judged against a schema the case was never
     * derived from.
     */
    record Choice(Schema<?> branch, String discriminatorProperty, String discriminatorValue, String unsupportedReason) {

        boolean fuzzable() {
            return unsupportedReason == null;
        }
    }

    static boolean branching(Schema<?> schema) {
        return schema != null
                && ((schema.getOneOf() != null && !schema.getOneOf().isEmpty())
                || (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()));
    }

    static String keyword(Schema<?> schema) {
        return schema != null && schema.getOneOf() != null && !schema.getOneOf().isEmpty() ? "oneOf" : "anyOf";
    }

    static List<Schema> branches(Schema<?> schema) {
        return schema.getOneOf() != null && !schema.getOneOf().isEmpty() ? schema.getOneOf() : schema.getAnyOf();
    }

    /**
     * Always picks the first branch — the choice has to be deterministic so the
     * same document produces the same control on every run — and then decides
     * whether that choice is defensible enough to fuzz inside.
     */
    Choice choose(Schema<?> schema) {
        List<Schema> branches = branches(schema);
        Schema<?> first = branches.getFirst();
        String keyword = keyword(schema);

        if (schema.getDiscriminator() == null || schema.getDiscriminator().getPropertyName() == null) {
            return new Choice(first, null, null,
                    "the " + keyword + " declares no discriminator, so a value invalid for the chosen branch may "
                            + "still satisfy another and no rejection can be proven");
        }

        String property = schema.getDiscriminator().getPropertyName();
        Optional<String> pinned = pinnedValue(first, property);
        if (pinned.isEmpty()) {
            return new Choice(first, property, null,
                    ("the discriminator '%s' is not pinned to a single value in the first branch, so the request "
                            + "cannot be shown to select that branch alone").formatted(property));
        }

        String value = pinned.get();
        for (int index = 1; index < branches.size(); index++) {
            if (!excludes(branches.get(index), property, value)) {
                return new Choice(first, property, value,
                        ("branch %d does not exclude discriminator '%s' = '%s', so a mutation of the chosen branch "
                                + "may still be valid there").formatted(index, property, value));
            }
        }

        return new Choice(first, property, value, null);
    }

    /** The single value this branch allows for the discriminating property, if there is one. */
    private Optional<String> pinnedValue(Schema<?> branch, String property) {
        return property(branch, property)
                .flatMap(SchemaFacts::enumValues)
                .filter(values -> values.size() == 1)
                .map(List::getFirst);
    }

    /** Whether this branch provably refuses the chosen discriminator value. */
    private boolean excludes(Schema<?> branch, String property, String value) {
        return property(branch, property)
                .flatMap(SchemaFacts::enumValues)
                .map(values -> !values.contains(value))
                // a branch that leaves the property unconstrained — or omits it
                // entirely — accepts the value along with everything else
                .orElse(false);
    }

    private Optional<Schema<?>> property(Schema<?> branch, String name) {
        Schema<?> resolved = effective.apply(branch);
        if (resolved == null) {
            return Optional.empty();
        }
        Map<String, Schema> properties = resolved.getProperties();
        return properties == null ? Optional.empty() : Optional.ofNullable(properties.get(name));
    }
}
