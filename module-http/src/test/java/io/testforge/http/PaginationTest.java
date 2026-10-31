package io.testforge.http;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.restassured.response.Response;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A server holding 5 orders (o-1..o-5), paged every way APIs page. */
class PaginationTest {

    record Order(String id) {
    }

    private HttpServer server;
    private final List<String> calls = new ArrayList<>();

    private static String items(int from, int to) {
        return IntStream.rangeClosed(from, Math.min(to, 5)).filter(i -> i >= 1)
                .mapToObj(i -> "{\"id\":\"o-" + i + "\"}").collect(Collectors.joining(",", "[", "]"));
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        return raw == null ? Map.of() : Arrays.stream(raw.split("&")).map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(kv -> kv[0], kv -> URLDecoder.decode(kv[1], StandardCharsets.UTF_8)));
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/by-page", exchange -> {
            int page = Integer.parseInt(query(exchange).get("page"));      // 1-based, 2 per page
            respond(exchange, 200, "{\"items\":" + (page < 1 ? "[]" : items(page * 2 - 1, page * 2)) + "}", null);
        });
        server.createContext("/by-offset", exchange -> {
            int offset = Integer.parseInt(query(exchange).get("offset"));
            respond(exchange, 200, "{\"data\":" + items(offset + 1, offset + 2) + "}", null);
        });
        server.createContext("/by-cursor", exchange -> {
            String cursor = query(exchange).getOrDefault("cursor", "start");
            String body = switch (cursor) {
                case "start" -> "{\"items\":" + items(1, 2) + ",\"next\":\"c2\"}";
                case "c2" -> "{\"items\":" + items(3, 4) + ",\"next\":\"c3\"}";
                default -> "{\"items\":" + items(5, 5) + ",\"next\":null}";
            };
            respond(exchange, 200, body, null);
        });
        server.createContext("/by-link", exchange -> {
            int page = Integer.parseInt(query(exchange).getOrDefault("page", "1"));
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/by-link";
            String link = page < 3
                    ? "<" + base + "?page=" + (page + 1) + ">; rel=\"next\", <" + base + "?page=3>; rel=\"last\""
                    : "<" + base + "?page=1>; rel=\"first\"";
            respond(exchange, 200, items(page * 2 - 1, page * 2), link);
        });
        server.createContext("/ignores-page", exchange -> respond(exchange, 200, "{\"items\":" + items(1, 2) + "}", null));
        server.createContext("/broken", exchange ->
                respond(exchange, query(exchange).get("page").equals("2") ? 500 : 200, "{\"items\":" + items(1, 2) + "}",
                        null));
        server.start();
    }

    private void respond(HttpExchange exchange, int status, String body, String link) throws IOException {
        calls.add(exchange.getRequestURI().toString());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (link != null) {
            exchange.getResponseHeaders().add("Link", link);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Response get(String pathAndQuery) {
        return given().baseUri("http://127.0.0.1:" + server.getAddress().getPort()).get(pathAndQuery);
    }

    @Test
    void pageNumbersUntilAnEmptyPage() {
        List<Order> all = Pagination.byPageNumber(page -> get("/by-page?page=" + page))
                .items("items", Order.class).collect();

        assertThat(all).extracting(Order::id).containsExactly("o-1", "o-2", "o-3", "o-4", "o-5");
        assertThat(calls).hasSize(4);
    }

    @Test
    void offsetsUntilAShortPageWithoutAnExtraCall() {
        List<Map> all = Pagination.byOffset(offset -> get("/by-offset?offset=" + offset + "&limit=2"), 2)
                .items("data", Map.class).collect();

        assertThat(all).hasSize(5);
        assertThat(calls).containsExactly("/by-offset?offset=0&limit=2", "/by-offset?offset=2&limit=2",
                "/by-offset?offset=4&limit=2");
    }

    @Test
    void cursorsUntilNoneIsReturned() {
        List<Order> all = Pagination.byCursor(cursor -> get(cursor == null ? "/by-cursor" : "/by-cursor?cursor=" + cursor),
                "next").items("items", Order.class).collect();

        assertThat(all).extracting(Order::id).containsExactly("o-1", "o-2", "o-3", "o-4", "o-5");
        assertThat(calls).containsExactly("/by-cursor", "/by-cursor?cursor=c2", "/by-cursor?cursor=c3");
    }

    @Test
    void linkHeadersFollowRelNextOnARootArray() {
        List<Order> all = Pagination.byLinkHeader(url -> url == null ? get("/by-link") : given().get(url))
                .items("$", Order.class).collect();

        assertThat(all).extracting(Order::id).containsExactly("o-1", "o-2", "o-3", "o-4", "o-5");
        assertThat(calls).containsExactly("/by-link", "/by-link?page=2", "/by-link?page=3");
    }

    @Test
    void aServerThatIgnoresThePageParameterFailsInsteadOfLoopingForever() {
        assertThatThrownBy(() -> Pagination.byPageNumber(page -> get("/ignores-page?page=" + page))
                .items("items", Map.class).collect())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Page 2 is identical to page 1");
    }

    @Test
    void theMaxPagesLimitStopsARunawayList() {
        assertThatThrownBy(() -> Pagination.byPageNumber(page -> get("/by-page?page=" + page))
                .items("items", Map.class).maxPages(2).collect())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Stopped after 2 pages");
    }

    @Test
    void aFailingPageNamesItsNumberAndStatus() {
        assertThatThrownBy(() -> Pagination.byPageNumber(page -> get("/broken?page=" + page))
                .items("items", Map.class).collect())
                .isInstanceOf(AssertionError.class)
                .hasMessage("Page 2 answered 500");
    }

    @Test
    void startAtZeroForZeroBasedApis() {
        assertThat(Pagination.byPageNumber(page -> get("/by-page?page=" + (page + 1)))
                .items("items", Order.class).startAt(0).collect()).hasSize(5);
    }

    @Test
    void parsesTheNextRelationAmongOthers() {
        assertThat(Pagination.nextLink("<https://a/x?p=1>; rel=\"prev\", <https://a/x?p=3>; rel=\"next\""))
                .isEqualTo("https://a/x?p=3");
        assertThat(Pagination.nextLink("<https://a/x?p=1>; rel=\"first\"")).isNull();
        assertThat(Pagination.nextLink(null)).isNull();
    }
}
