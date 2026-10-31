package io.testforge.http;

import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Walks every page of a list endpoint with one call, so a test that needs
 * "all orders" does not hand-write the loop — or its two classic bugs: a
 * missed last page, and an endless loop against a server that ignores the
 * page parameter.
 *
 * <pre>{@code
 * List<Order> all = Pagination.byPageNumber(page -> api.request().queryParam("page", page).get("/orders"))
 *         .items("items", Order.class)
 *         .collect();
 * }</pre>
 *
 * Stops at an empty page (page number), a short page (offset), a missing
 * cursor (cursor) or a missing {@code rel="next"} link (Link header). Fails
 * with an {@link AssertionError} on a non-2xx page, on the same page served
 * twice, and after {@code maxPages} pages (default 100).
 */
public final class Pagination<T> {

    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>\\s*;[^,]*rel=\"?next\"?");

    private enum Strategy { PAGE_NUMBER, OFFSET, CURSOR, LINK_HEADER }

    private final Strategy strategy;
    private final Function<Object, Response> call;
    private final int limit;
    private final String cursorPath;
    private String itemsPath = "$";
    private Class<T> itemType;
    private int first;
    private int maxPages = 100;

    private Pagination(Strategy strategy, Function<Object, Response> call, int limit, String cursorPath,
                       int first) {
        this.strategy = strategy;
        this.call = call;
        this.limit = limit;
        this.cursorPath = cursorPath;
        this.first = first;
    }

    /** {@code page -> request with ?page=page}; starts at 1 (see {@link #startAt}), stops at an empty page. */
    public static Pagination<Object> byPageNumber(IntFunction<Response> page) {
        Objects.requireNonNull(page, "page");
        return new Pagination<>(Strategy.PAGE_NUMBER, number -> page.apply((Integer) number), 0, null, 1);
    }

    /** {@code offset -> request with ?offset=offset&limit=limit}; stops at a page shorter than {@code limit}. */
    public static Pagination<Object> byOffset(IntFunction<Response> offset, int limit) {
        Objects.requireNonNull(offset, "offset");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return new Pagination<>(Strategy.OFFSET, value -> offset.apply((Integer) value), limit, null, 0);
    }

    /**
     * {@code cursor -> request}: called with {@code null} first, then with the
     * value at {@code nextCursorPath} (REST Assured GPath) until it is missing or blank.
     */
    public static Pagination<Object> byCursor(Function<String, Response> cursor, String nextCursorPath) {
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(nextCursorPath, "nextCursorPath");
        return new Pagination<>(Strategy.CURSOR, value -> cursor.apply((String) value), 0, nextCursorPath, 0);
    }

    /**
     * {@code url -> request}: called with {@code null} for the first page, then
     * with the {@code rel="next"} URL from the {@code Link} header (RFC 8288).
     */
    public static Pagination<Object> byLinkHeader(Function<String, Response> url) {
        Objects.requireNonNull(url, "url");
        return new Pagination<>(Strategy.LINK_HEADER, value -> url.apply((String) value), 0, null, 0);
    }

    /** Where the page's items are (GPath, {@code $} for a root array — the default) and what they map to. */
    @SuppressWarnings("unchecked")
    public <R> Pagination<R> items(String path, Class<R> type) {
        Pagination<R> typed = (Pagination<R>) this;
        typed.itemsPath = Objects.requireNonNull(path, "path");
        typed.itemType = Objects.requireNonNull(type, "type");
        return typed;
    }

    /** First page number for {@link #byPageNumber}; 1 by default. */
    public Pagination<T> startAt(int firstPage) {
        this.first = firstPage;
        return this;
    }

    public Pagination<T> maxPages(int pages) {
        if (pages <= 0) {
            throw new IllegalArgumentException("maxPages must be positive");
        }
        this.maxPages = pages;
        return this;
    }

    /** Every item of every page, in order. */
    public List<T> collect() {
        List<T> all = new ArrayList<>();
        Object position = switch (strategy) {
            case PAGE_NUMBER -> first;
            case OFFSET -> 0;
            case CURSOR, LINK_HEADER -> null;
        };
        String previousBody = null;
        for (int page = 1; ; page++) {
            if (page > maxPages) {
                throw new AssertionError("Stopped after %d pages without reaching the last one; raise maxPages(...)"
                        .formatted(maxPages) + " if the list is really that long");
            }
            Response response = call.apply(position);
            if (response.getStatusCode() / 100 != 2) {
                throw new AssertionError("Page %d answered %d".formatted(page, response.getStatusCode()));
            }
            String body = response.asString();
            List<T> items = items(response);
            if (!items.isEmpty() && body.equals(previousBody)) {
                throw new AssertionError(("Page %d is identical to page %d: the server ignores the paging "
                        + "parameter, so walking on would never end").formatted(page, page - 1));
            }
            previousBody = body;
            all.addAll(items);

            position = next(response, items, position);
            if (position == null) {
                return List.copyOf(all);
            }
        }
    }

    private Object next(Response response, List<T> items, Object position) {
        return switch (strategy) {
            case PAGE_NUMBER -> items.isEmpty() ? null : (Integer) position + 1;
            case OFFSET -> items.size() < limit ? null : (Integer) position + items.size();
            case CURSOR -> {
                Object cursor = response.jsonPath().get(cursorPath);
                yield cursor == null || String.valueOf(cursor).isBlank() || items.isEmpty() ? null : String.valueOf(cursor);
            }
            case LINK_HEADER -> nextLink(response.getHeader("Link"));
        };
    }

    static String nextLink(String header) {
        if (header == null) {
            return null;
        }
        Matcher matcher = NEXT_LINK.matcher(header);
        return matcher.find() ? matcher.group(1) : null;
    }

    @SuppressWarnings("unchecked")
    private List<T> items(Response response) {
        Class<T> type = itemType == null ? (Class<T>) Object.class : itemType;
        List<T> items = response.jsonPath().getList(itemsPath, type);
        return items == null ? List.of() : items;
    }
}
