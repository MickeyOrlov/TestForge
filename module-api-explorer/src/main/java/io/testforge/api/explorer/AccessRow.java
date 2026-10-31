package io.testforge.api.explorer;

import java.util.List;

/** One operation and what every role got from it. */
public record AccessRow(String specId, String operationId, String key, String target, List<AccessCell> cells) {

    public AccessRow {
        cells = List.copyOf(cells);
    }
}
