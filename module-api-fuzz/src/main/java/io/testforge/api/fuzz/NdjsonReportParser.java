package io.testforge.api.fuzz;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class NdjsonReportParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public ApiFuzzReport parse(Path reportPath) {
        if (!Files.exists(reportPath) || !Files.isRegularFile(reportPath)) {
            throw new ApiFuzzException("NDJSON report file is missing or not a regular file: " + reportPath);
        }

        long linesRead = 0;
        String schemathesisVersion = null;
        Long seed = null;
        List<ApiFuzzFinding> findings = new ArrayList<>();
        boolean hasNonFatalError = false;
        Double runningTime = null;

        try (BufferedReader reader = Files.newBufferedReader(reportPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                linesRead++;
                
                JsonNode eventNode;
                try {
                    eventNode = mapper.readTree(line);
                } catch (JsonProcessingException e) {
                    throw new ApiFuzzException("Failed to parse JSON on line " + linesRead + " in file: " + reportPath, e);
                }
                
                if (!eventNode.isObject() || eventNode.isEmpty()) {
                    continue;
                }

                String eventType = eventNode.fieldNames().next();
                JsonNode payload = eventNode.get(eventType);

                switch (eventType) {
                    case "Initialize":
                        if (payload.has("schemathesis_version") && !payload.get("schemathesis_version").isNull()) {
                            schemathesisVersion = payload.get("schemathesis_version").asText();
                        }
                        if (payload.has("seed") && !payload.get("seed").isNull()) {
                            seed = payload.get("seed").asLong();
                        }
                        break;
                    case "ScenarioFinished":
                        processScenarioFinished(payload, findings);
                        break;
                    case "NonFatalError":
                        hasNonFatalError = true;
                        break;
                    case "EngineFinished":
                        if (payload.has("running_time") && !payload.get("running_time").isNull()) {
                            runningTime = payload.get("running_time").asDouble();
                        }
                        break;
                    default:
                        // Ignore unknown event types
                        break;
                }
            }
        } catch (IOException e) {
            throw new ApiFuzzException("Failed to read NDJSON report file: " + reportPath, e);
        }

        if (linesRead == 0) {
            throw new ApiFuzzException("NDJSON report file is empty: " + reportPath);
        }

        ApiFuzzOutcome outcome = ApiFuzzOutcome.PASSED;
        if (hasNonFatalError) {
            outcome = ApiFuzzOutcome.EXECUTION_ERROR;
        } else if (!findings.isEmpty()) {
            outcome = ApiFuzzOutcome.FINDINGS;
        }

        Duration duration = runningTime != null ? Duration.ofMillis((long) (runningTime * 1000)) : null;

        return new ApiFuzzReport(
            null, // runId
            null, // specId
            outcome,
            schemathesisVersion,
            seed,
            Collections.emptyList(), // phases
            0, // totalScenarios
            0, // failedScenarios
            findings,
            Collections.emptyList(), // errors
            Collections.emptyMap(), // artifacts
            duration
        );
    }

    private void processScenarioFinished(JsonNode payload, List<ApiFuzzFinding> findings) {
        if (!payload.has("status") || !"failure".equals(payload.get("status").asText())) {
            return;
        }

        String phase = payload.has("phase") && !payload.get("phase").isNull() ? payload.get("phase").asText() : null;
        JsonNode recorder = payload.get("recorder");
        if (recorder == null || !recorder.isObject()) {
            return;
        }

        String label = recorder.has("label") && !recorder.get("label").isNull() ? recorder.get("label").asText() : null;
        
        JsonNode cases = recorder.get("cases");
        JsonNode checks = recorder.get("checks");
        
        if (checks == null || !checks.isObject()) {
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> checksIterator = checks.fields();
        while (checksIterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = checksIterator.next();
            String caseId = entry.getKey();
            JsonNode caseChecks = entry.getValue();
            
            if (caseChecks == null || !caseChecks.isArray()) {
                continue;
            }

            String method = null;
            String path = null;
            if (cases != null && cases.has(caseId)) {
                JsonNode caseNode = cases.get(caseId);
                if (caseNode.has("value")) {
                    JsonNode valueNode = caseNode.get("value");
                    if (valueNode.has("method") && !valueNode.get("method").isNull()) {
                        method = valueNode.get("method").asText();
                    }
                    if (valueNode.has("path") && !valueNode.get("path").isNull()) {
                        path = valueNode.get("path").asText();
                    }
                }
            }

            for (JsonNode check : caseChecks) {
                if (check.has("status") && "failure".equals(check.get("status").asText())) {
                    String checkName = check.has("name") && !check.get("name").isNull() ? check.get("name").asText() : null;
                    String message = null;
                    if (check.has("failure_info") && !check.get("failure_info").isNull()) {
                        JsonNode failureInfo = check.get("failure_info");
                        if (failureInfo.has("failure") && !failureInfo.get("failure").isNull()) {
                            JsonNode failure = failureInfo.get("failure");
                            if (failure.has("message") && !failure.get("message").isNull()) {
                                message = failure.get("message").asText();
                            }
                        }
                    }
                    
                    findings.add(new ApiFuzzFinding(label, phase, method, path, checkName, message));
                }
            }
        }
    }
}
