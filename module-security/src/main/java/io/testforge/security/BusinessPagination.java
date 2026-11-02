package io.testforge.security;

/** Cursor values remain in memory; only a declared query parameter changes, never a URL/path. */
public record BusinessPagination(
        String items,
        String next,
        String parameter,
        Integer maxPages,
        Integer maxItems,
        Boolean requireItems) {
    public BusinessPagination {
        BusinessSecuritySuite.pointer(items);
        BusinessSecuritySuite.pointer(next);
        if (items.isEmpty() || next.isEmpty() || items.equals(next) || next.startsWith(items + "/"))
            throw new BusinessSecurityException(
                    "pagination needs distinct object-field items and cursor pointers");
        for (String pointer : java.util.List.of(items, next))
            for (String field : pointer.substring(1).split("/", -1))
                if (!field.matches("[a-zA-Z_][a-zA-Z0-9_-]{0,63}"))
                    throw new BusinessSecurityException(
                            "pagination pointers must use object field names");
        if (parameter == null || !parameter.matches("[a-zA-Z_][a-zA-Z0-9_-]{0,63}"))
            throw new BusinessSecurityException("pagination needs a structural query parameter");
        maxPages = maxPages == null ? 20 : maxPages;
        maxItems = maxItems == null ? 1000 : maxItems;
        if (maxPages < 1 || maxPages > 100 || maxItems < 1 || maxItems > 10000)
            throw new BusinessSecurityException("pagination limits outside supported range");
        requireItems = requireItems == null || requireItems;
    }
}
