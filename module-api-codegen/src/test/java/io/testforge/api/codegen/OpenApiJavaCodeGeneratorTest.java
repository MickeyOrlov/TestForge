package io.testforge.api.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.api.discovery.ApiSpecSource;
import io.testforge.api.discovery.OpenApiSpecParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenApiJavaCodeGeneratorTest {

    @TempDir
    Path temp;

    @Test
    void generatesCompilableRecordsAndTypedClient() throws IOException {
        Path spec = temp.resolve("booking.yaml");
        Files.writeString(spec, bookingSpec());
        OpenAPI openApi = new OpenApiSpecParser().parse(new ApiSpecSource("booking", spec.toString()));

        GeneratedApiSources generated = new OpenApiJavaCodeGenerator()
                .generate("booking", openApi, "io.testforge.generated");

        assertThat(generated.operationCount()).isEqualTo(2);
        assertThat(generated.clientCount()).isEqualTo(1);
        assertThat(generated.sources())
                .extracting(GeneratedSource::relativePath)
                .anyMatch(path -> path.endsWith("/model/BookingRequest.java"))
                .anyMatch(path -> path.endsWith("/model/BookingDates.java"))
                .anyMatch(path -> path.endsWith("/model/BookingResponseHistoryItemItem.java"))
                .anyMatch(path -> path.endsWith("/client/BookingsApiClient.java"));

        String request = source(generated, "/model/BookingRequest.java");
        assertThat(request)
                .contains("int totalprice")
                .contains("boolean depositpaid")
                .contains("BookingDates bookingdates")
                .contains("@JsonProperty(\"first-name\") String firstName")
                .contains("@JsonProperty(\"first_name\") String firstName2");

        String client = source(generated, "/client/BookingsApiClient.java");
        assertThat(client)
                .contains("Response createBooking(BookingRequest request)")
                .contains("public Response getBooking(")
                .contains("int bookingId,")
                .contains("String expand) {")
                .contains("requestSpec.pathParam(\"booking-id\", bookingId)")
                .contains("if (expand != null)")
                .contains("requestSpec.post(\"/booking\")")
                .contains("requestSpec.get(\"/booking/{booking-id}\")");

        assertCompiles(generated.sources());
    }

    private String source(GeneratedApiSources generated, String suffix) {
        return generated.sources().stream()
                .filter(source -> source.relativePath().endsWith(suffix))
                .findFirst()
                .orElseThrow()
                .content();
    }

    private void assertCompiles(List<GeneratedSource> sources) throws IOException {
        Path sourceRoot = temp.resolve("generated");
        Path classes = temp.resolve("classes");
        Files.createDirectories(classes);
        List<String> arguments = new ArrayList<>(List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        for (GeneratedSource source : sources) {
            Path file = sourceRoot.resolve(source.relativePath());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.content(), StandardCharsets.UTF_8);
            arguments.add(file.toString());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK compiler").isNotNull();
        int exitCode = compiler.run(null, null, null, arguments.toArray(String[]::new));
        assertThat(exitCode).isZero();
    }

    private String bookingSpec() {
        return """
                openapi: 3.0.3
                info:
                  title: Booking API
                  version: 1.0.0
                paths:
                  /booking:
                    post:
                      operationId: createBooking
                      tags: [bookings]
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              $ref: '#/components/schemas/BookingRequest'
                      responses:
                        '200':
                          description: Created
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/BookingResponse'
                  /booking/{booking-id}:
                    get:
                      operationId: getBooking
                      tags: [bookings]
                      parameters:
                        - name: booking-id
                          in: path
                          required: true
                          schema:
                            type: integer
                        - name: expand
                          in: query
                          schema:
                            type: string
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/BookingResponse'
                components:
                  schemas:
                    BookingDates:
                      type: object
                      required: [checkin, checkout]
                      properties:
                        checkin:
                          type: string
                          format: date
                        checkout:
                          type: string
                          format: date
                    BookingRequest:
                      type: object
                      required: [first-name, totalprice, depositpaid, bookingdates]
                      properties:
                        first-name:
                          type: string
                        first_name:
                          type: string
                        totalprice:
                          type: integer
                          format: int32
                        depositpaid:
                          type: boolean
                        bookingdates:
                          $ref: '#/components/schemas/BookingDates'
                        notes:
                          type: array
                          items:
                            type: string
                    BookingResponse:
                      type: object
                      required: [bookingid, booking]
                      properties:
                        bookingid:
                          type: integer
                        booking:
                          $ref: '#/components/schemas/BookingRequest'
                        history:
                          type: array
                          items:
                            type: array
                            items:
                              type: object
                              required: [code]
                              properties:
                                code:
                                  type: string
                """;
    }
}
