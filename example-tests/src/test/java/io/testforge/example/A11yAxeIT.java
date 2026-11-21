package io.testforge.example;

import static org.assertj.core.api.Assertions.*;
import com.microsoft.playwright.Page;
import io.testforge.a11y.*;
import io.testforge.web.playwright.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;

/** Real axe, a local static DOM and the existing Page lifecycle. No external site is contacted. */
@Tag("browser")
@SpringBootTest(properties = {
    "forge.playwright.enabled=true", "forge.playwright.download-browsers=false",
    "forge.playwright.artifacts-on-failure=false" })
@ExtendWith(PlaywrightPageExtension.class)
class A11yAxeIT {
    @TempDir Path dir;
    @Test void missingAltAndLowContrastCanBeAdoptedButNewViolationsStillFail(Page page) {
        var properties = new A11yProperties(true, null, null, "serious", dir.resolve("baseline.json").toString(),
                dir.resolve("reports").toString(), List.of("/fixture"), null, null);
        var a11y = new A11y(properties, new AxeEngine(), null);
        page.setContent("""
                <!doctype html><html lang="en"><head><title>Axe fixture</title></head>
                <body><main><h1>Accessibility fixture</h1><img id="missing-alt" src="data:image/gif;base64,R0lGODlhAQABAAAAACw=">
                <button id="contrast" style="color:#eeeeee;background:#ffffff;border:0">Continue</button></main></body></html>
                """);
        var unknownTags = new A11yProperties(true, List.of("unknown-tag"), null, null, null,
                dir.resolve("bad-tags").toString(), null, null, null);
        assertThatThrownBy(() -> new A11y(unknownTags, new AxeEngine(), null).check(page, "/fixture"))
                .isInstanceOf(IllegalStateException.class);
        var initial = a11y.check(page, "/fixture");
        assertThat(initial.violations()).extracting(A11yResult.Finding::rule).contains("image-alt", "color-contrast");
        assertThatThrownBy(() -> a11y.assertNoNewViolations(page, "/fixture")).isInstanceOf(AssertionError.class);
        a11y.writeBaseline(initial);
        assertThat(a11y.assertNoNewViolations(page, "/fixture").passed()).isTrue();
        page.locator("main").evaluate("element => {const input=document.createElement('input'); input.id='new-unlabelled'; element.appendChild(input);}");
        assertThatThrownBy(() -> a11y.assertNoNewViolations(page, "/fixture")).isInstanceOf(AssertionError.class);
        var scopedPolicy = new A11yProperties(true, null, null, "serious", null,
                dir.resolve("scoped").toString(), null, null, null);
        var scoped = new A11y(scopedPolicy, new AxeEngine(), null)
                .check(page, "/scoped", List.of("main"), List.of("#new-unlabelled"));
        assertThat(scoped.violations()).noneMatch(v -> v.rule().equals("label"));
    }
}
