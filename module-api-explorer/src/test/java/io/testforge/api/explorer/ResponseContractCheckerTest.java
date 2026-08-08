package io.testforge.api.explorer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The runtime contract checks: what the service really answered against what
 * its own document promised.
 */
class ResponseContractCheckerTest {

    private final ResponseContractChecker checker = new ResponseContractChecker(new ObjectMapper());

    @Test
    void aMatchingResponseProducesNothing() {
        assertThat(check("getTask", 200, "application/json", """
                {"id":"task-1","title":"Write tests","priority":3}"""))
                .isEmpty();
    }

    @Test
    void nullIsAcceptedWhereTheSchemaDeclaresItNullable() {
        assertThat(check("getTask", 200, "application/json", """
                {"id":"task-1","title":"Write tests","note":null}"""))
                .isEmpty();
    }

    @Test
    void undocumentedStatusIsReported() {
        List<ContractMismatch> mismatches = check("getTask", 500, "application/json", "{}");

        assertThat(mismatches).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.kind()).isEqualTo(MismatchKind.UNDOCUMENTED_STATUS);
            assertThat(mismatch.detail()).contains("500");
        });
    }

    @Test
    void unexpectedContentTypeIsReported() {
        List<ContractMismatch> mismatches = check("getTask", 200, "text/html", "<html></html>");

        assertThat(mismatches).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.kind()).isEqualTo(MismatchKind.UNEXPECTED_CONTENT_TYPE);
            assertThat(mismatch.detail()).contains("text/html").contains("application/json");
        });
    }

    @Test
    void missingRequiredFieldIsReported() {
        List<ContractMismatch> mismatches = check("getTask", 200, "application/json", """
                {"id":"task-1"}""");

        assertThat(mismatches).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.kind()).isEqualTo(MismatchKind.MISSING_REQUIRED_FIELD);
            assertThat(mismatch.location()).isEqualTo("$.title");
        });
    }

    @Test
    void undocumentedFieldIsReported() {
        List<ContractMismatch> mismatches = check("getTask", 200, "application/json", """
                {"id":"task-1","title":"Write tests","assignee":"nobody"}""");

        assertThat(mismatches).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.kind()).isEqualTo(MismatchKind.UNDOCUMENTED_FIELD);
            assertThat(mismatch.location()).isEqualTo("$.assignee");
        });
    }

    @Test
    void incompatibleFieldTypeIsReported() {
        List<ContractMismatch> mismatches = check("getTask", 200, "application/json", """
                {"id":"task-1","title":"Write tests","priority":"high"}""");

        assertThat(mismatches).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.kind()).isEqualTo(MismatchKind.INCOMPATIBLE_FIELD_TYPE);
            assertThat(mismatch.location()).isEqualTo("$.priority");
            assertThat(mismatch.detail()).contains("integer").contains("string");
        });
    }

    @Test
    void bodyThatIsNotTheJsonItClaimsToBeIsReported() {
        List<ContractMismatch> mismatches = check("getTask", 200, "application/json", "not json at all");

        assertThat(mismatches).singleElement()
                .satisfies(mismatch -> assertThat(mismatch.kind()).isEqualTo(MismatchKind.MALFORMED_BODY));
    }

    @Test
    void findingsInsideArraysCollapseOntoOnePath() {
        List<ContractMismatch> mismatches = check("listTasks", 200, "application/json", """
                {"items":[{"id":"a"},{"id":"b"},{"id":"c"}]}""");

        // three bad elements are one defect in the report, not three lines
        assertThat(mismatches).singleElement().satisfies(mismatch -> {
            assertThat(mismatch.kind()).isEqualTo(MismatchKind.MISSING_REQUIRED_FIELD);
            assertThat(mismatch.location()).isEqualTo("$.items[].title");
        });
    }

    @Test
    void aDocumentedStatusWithoutABodyDeclarationIsAccepted() {
        assertThat(check("deleteTask", 204, null, "")).isEmpty();
    }

    private List<ContractMismatch> check(String operationId, int status, String contentType, String body) {
        RuntimeExchange exchange = new RuntimeExchange(
                Map.of(), null, status, contentType, Map.of(), body, 5L, null);
        return checker.check(ExplorerFixtures.operation(operationId), exchange);
    }
}
