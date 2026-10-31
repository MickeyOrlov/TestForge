package io.testforge.api.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.api.codegen.GeneratedSurface.TypeSurface;
import io.testforge.api.discovery.ApiSpecSource;
import io.testforge.api.discovery.OpenApiSpecParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedSurfaceTest {

    @TempDir
    Path temp;

    @Test
    void eachChangeIsJudgedForTheTestsCompiledAgainstTheOldSources() {
        GeneratedSurface before = new GeneratedSurface(Map.of(
                "TasksApiClient", client(Map.of(
                        "getTask", "(String id, String expand)",
                        "search", "(String owner, String status)",
                        "archive", "(String id)")),
                "CreateTaskRequest", record(true, Map.of("title", "String")),
                "Task", record(false, Map.of("id", "String", "title", "String")),
                "Legacy", record(false, Map.of())));
        GeneratedSurface after = new GeneratedSurface(Map.of(
                "TasksApiClient", client(Map.of(
                        "getTask", "(long id, String expand)",
                        "search", "(String status, String owner)",
                        "listTasks", "()")),
                "CreateTaskRequest", record(true, Map.of("title", "String", "priority", "Integer")),
                "Task", record(false, Map.of("id", "String", "title", "String", "status", "String")),
                "Comment", record(false, Map.of("text", "String"))));

        assertThat(after.changesSince(before)).extracting(change -> change.impact() + " " + change.location())
                .containsExactly(
                        "BREAKING CreateTaskRequest.priority",
                        "BREAKING Legacy",
                        "BREAKING TasksApiClient.archive",
                        "BREAKING TasksApiClient.getTask",
                        "RISKY TasksApiClient.search",
                        "SAFE Comment",
                        "SAFE Task.status",
                        "SAFE TasksApiClient.listTasks");
        assertThat(after.changesSince(before)).filteredOn(change -> change.location().equals("TasksApiClient.search"))
                .singleElement().satisfies(change -> assertThat(change.detail())
                        .contains("(String owner, String status) -> (String status, String owner)")
                        .contains("wrong parameter"));
        assertThat(after.changesSince(before))
                .filteredOn(change -> change.location().equals("CreateTaskRequest.priority"))
                .singleElement().satisfies(change -> assertThat(change.detail())
                        .contains("new CreateTaskRequest(...) call needs one more argument"));
    }

    @Test
    void anUnchangedSurfaceHasNoChanges() {
        GeneratedSurface surface = new GeneratedSurface(Map.of("Task", record(false, Map.of("id", "String"))));

        assertThat(surface.changesSince(surface)).isEmpty();
    }

    @Test
    void parameterTypesKeepGenericArgumentsWhole() {
        assertThat(GeneratedSurface.parameterTypes("(Map<String, List<Task>> byOwner, int page)"))
                .containsExactly("Map<String, List<Task>>", "int");
        assertThat(GeneratedSurface.parameterTypes("()")).isEmpty();
    }

    @Test
    void recordsReachableFromARequestBodyAreTheOnesTestsBuild() throws IOException {
        Path spec = temp.resolve("surface.yaml");
        Files.writeString(spec, """
                openapi: 3.0.3
                info: {title: Surface, version: 1.0.0}
                paths:
                  /bookings:
                    post:
                      operationId: createBooking
                      tags: [bookings]
                      parameters:
                        - {name: dryRun, in: query, schema: {type: boolean}}
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/BookingRequest'}
                      responses:
                        '201':
                          description: Created
                          content:
                            application/json:
                              schema: {$ref: '#/components/schemas/Booking'}
                components:
                  schemas:
                    BookingRequest:
                      type: object
                      required: [dates]
                      properties:
                        dates: {$ref: '#/components/schemas/Dates'}
                        notes: {type: array, items: {type: string}}
                    Dates:
                      type: object
                      properties:
                        checkin: {type: string, format: date}
                    Booking:
                      type: object
                      properties:
                        id: {type: integer, format: int64}
                        dates: {$ref: '#/components/schemas/Dates'}
                """);
        OpenAPI openApi = new OpenApiSpecParser().parse(new ApiSpecSource("surface", spec.toString()));

        GeneratedSurface surface = new OpenApiJavaCodeGenerator()
                .generate("surface", openApi, "io.testforge.generated").surface();

        assertThat(surface.types().get("BookingsApiClient").members())
                .containsExactly(Map.entry("createBooking", "(Boolean dryRun, BookingRequest request)"));
        assertThat(surface.types().get("BookingRequest").constructed()).isTrue();
        assertThat(surface.types().get("BookingRequest").members())
                .containsExactly(Map.entry("dates", "Dates"), Map.entry("notes", "List<String>"));
        assertThat(surface.types().get("Dates").constructed()).isTrue();
        assertThat(surface.types().get("Booking").constructed()).isFalse();
        assertThat(surface.types().get("Booking").members()).containsEntry("id", "Long");
    }

    private static TypeSurface client(Map<String, String> members) {
        return new TypeSurface("client", false, new LinkedHashMap<>(members));
    }

    private static TypeSurface record(boolean constructed, Map<String, String> members) {
        return new TypeSurface("record", constructed, new LinkedHashMap<>(members));
    }
}
