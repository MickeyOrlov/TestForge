package io.testforge.state;

import java.util.List;
import java.util.Optional;

/**
 * Immutable request passed to a state recipe. Tags usually come from
 * {@code @Prepared(tags = ...)} and may contain both the requested state and
 * data flavours.
 */
public record StateRequest(List<String> tags, String targetTagPrefix) {

    public StateRequest {
        tags = List.copyOf(tags == null ? List.of() : tags);
        if (targetTagPrefix == null || targetTagPrefix.isBlank()) {
            targetTagPrefix = "state:";
        }
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    public Optional<String> targetTag() {
        Optional<String> prefixed = tags.stream()
                .filter(tag -> tag.startsWith(targetTagPrefix))
                .map(tag -> tag.substring(targetTagPrefix.length()))
                .findFirst();
        if (prefixed.isPresent()) {
            return prefixed;
        }
        return tags.size() == 1 ? Optional.of(tags.getFirst()) : Optional.empty();
    }

    public String requireTargetTag() {
        return targetTag().orElseThrow(() -> new StateRecipeException(
                "No target state tag found. Use a single tag like 'approved' or a prefixed tag like '%sapproved'. Tags: %s"
                        .formatted(targetTagPrefix, tags)));
    }
}
