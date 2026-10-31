package io.testforge.web.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Offline: the journal takes plain values, the fixture feeds it from page listeners. */
class BrowserJournalTest {

    @Test
    void onlyWhatHelpsExplainAFailureIsKept() {
        BrowserJournal journal = new BrowserJournal();
        journal.console("log", "app booted");
        journal.console("error", "Uncaught (in promise) TypeError: x is undefined\n    at app.js:12");
        journal.console("warning", "deprecated API");
        journal.response("GET", "https://shop.test/api/cart", 200);
        journal.response("POST", "https://shop.test/api/pay?token=abc", 502);
        journal.requestFailed("GET", "https://cdn.test/app.js", "net::ERR_CONNECTION_REFUSED");
        journal.pageError("ReferenceError: checkout is not defined");

        String report = journal.markdown("CheckoutTest-pays", "https://u:p@shop.test/checkout?session=s1", "Checkout");

        assertThat(report)
                .contains("# Browser — CheckoutTest-pays")
                .contains("- page: https://shop.test/checkout?session=***")
                .contains("- title: Checkout")
                .contains("- POST https://shop.test/api/pay?token=*** -> 502")
                .contains("- GET https://cdn.test/app.js -> failed: net::ERR_CONNECTION_REFUSED")
                .contains("- ReferenceError: checkout is not defined")
                .contains("- error: Uncaught (in promise) TypeError: x is undefined\n")
                .contains("- warning: deprecated API")
                .doesNotContain("app booted")
                .doesNotContain("/api/cart")
                .doesNotContain("abc")
                .doesNotContain("u:p")
                .doesNotContain("app.js:12");
    }

    @Test
    void itKeepsTheLastEntriesAndSaysHowManyWereDropped() {
        BrowserJournal journal = new BrowserJournal();
        for (int i = 0; i < BrowserJournal.LIMIT + 5; i++) {
            journal.response("GET", "https://shop.test/item/" + i, 404);
        }

        assertThat(journal.requests()).hasSize(BrowserJournal.LIMIT)
                .first().asString().contains("/item/5 ");
        assertThat(journal.markdown("t", null, null)).contains("- older entries dropped: 5");
    }

    @Test
    void anEmptyJournalSaysSoPerSection() {
        BrowserJournal journal = new BrowserJournal();

        assertThat(journal.isEmpty()).isTrue();
        assertThat(journal.markdown("t", "about:blank", "")).contains("## Failed requests\n\nNone.");
    }
}
