package io.testforge.example;

import io.testforge.quality.QualityRunner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The gate itself, run by the {@code qualityGate} task after the test tasks —
 * never by {@code test}, which excludes the tag. The task points it at the
 * finished run; the report is {@code build/quality/quality.md}.
 */
@SpringBootTest
@Tag("quality-gate")
class QualityGateTest {

    @Autowired
    QualityRunner quality;

    @Test
    void theFinishedRunIsAcceptable() {
        quality.assertAcceptable();
    }
}
