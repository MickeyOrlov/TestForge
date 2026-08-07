package io.testforge.api.codegen;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class OpenApiJavaCodeGenerator {

    public GeneratedApiSources generate(String specId, OpenAPI openApi, String basePackage) {
        Objects.requireNonNull(openApi, "openApi");
        String specPackage = basePackage + "." + JavaNames.packageSegment(specId);
        List<OperationDefinition> operations = operations(openApi);
        ModelRegistry models = new ModelRegistry(openApi, specPackage + ".model");

        models.registerComponents();
        operations.forEach(models::registerOperationSchemas);
        models.discoverNestedModels();

        List<GeneratedSource> sources = new ArrayList<>(models.render());
        List<GeneratedSource> clients = renderClients(operations, models, specPackage + ".client");
        sources.addAll(clients);
        sources.sort(Comparator.comparing(GeneratedSource::relativePath));

        return new GeneratedApiSources(
                specPackage,
                models.size(),
                clients.size(),
                operations.size(),
                sources,
                List.copyOf(models.warnings));
    }

    private List<OperationDefinition> operations(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return List.of();
        }
        List<OperationDefinition> operations = new ArrayList<>();
        openApi.getPaths().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(pathEntry -> {
                    PathItem pathItem = pathEntry.getValue();
                    if (pathItem == null) {
                        return;
                    }
                    pathItem.readOperationsMap().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(operation -> operations.add(new OperationDefinition(
                                    pathEntry.getKey(),
                                    operation.getKey(),
                                    pathItem,
                                    operation.getValue())));
                });
        return List.copyOf(operations);
    }

    private List<GeneratedSource> renderClients(
            List<OperationDefinition> operations,
            ModelRegistry models,
            String clientPackage) {
        Map<String, List<OperationDefinition>> byTag = new TreeMap<>();
        for (OperationDefinition operation : operations) {
            byTag.computeIfAbsent(tag(operation.operation()), ignored -> new ArrayList<>()).add(operation);
        }

        Set<String> usedClientNames = new LinkedHashSet<>();
        List<GeneratedSource> clients = new ArrayList<>();
        for (Map.Entry<String, List<OperationDefinition>> entry : byTag.entrySet()) {
            String clientName = unique(JavaNames.type(entry.getKey()) + "ApiClient", usedClientNames);
            clients.add(renderClient(clientPackage, clientName, entry.getValue(), models));
        }
        return List.copyOf(clients);
    }

    private GeneratedSource renderClient(
            String clientPackage,
            String clientName,
            List<OperationDefinition> operations,
            ModelRegistry models) {
        StringBuilder methods = new StringBuilder();
        Set<String> methodNames = new LinkedHashSet<>();
        boolean importsModels = false;

        for (OperationDefinition operation : operations) {
            ClientMethod method = clientMethod(operation, models, methodNames);
            importsModels |= method.usesModel();
            methods.append(renderMethod(method));
        }

        StringBuilder source = new StringBuilder();
        source.append("package ").append(clientPackage).append(";\n\n")
                .append("import io.restassured.response.Response;\n")
                .append("import io.restassured.specification.RequestSpecification;\n")
                .append("import io.testforge.http.ApiClient;\n");
        if (importsModels) {
            source.append("import ").append(models.modelPackage).append(".*;\n");
        }
        source.append("\n")
                .append("/** Generated from OpenAPI. Regenerate instead of editing this file. */\n")
                .append("public final class ").append(clientName).append(" {\n\n")
                .append("    private final ApiClient api;\n\n")
                .append("    public ").append(clientName).append("(ApiClient api) {\n")
                .append("        this.api = api;\n")
                .append("    }\n")
                .append(methods)
                .append("}\n");

        return new GeneratedSource(sourcePath(clientPackage, clientName), source.toString());
    }

    private ClientMethod clientMethod(
            OperationDefinition definition,
            ModelRegistry models,
            Set<String> usedMethodNames) {
        Operation operation = definition.operation();
        String requestedName = operation.getOperationId();
        if (requestedName == null || requestedName.isBlank()) {
            requestedName = definition.method().name().toLowerCase(Locale.ROOT) + " " + definition.path();
        }
        String methodName = unique(JavaNames.member(requestedName), usedMethodNames);

        List<MethodParameter> parameters = new ArrayList<>();
        Set<String> parameterNames = new LinkedHashSet<>();
        for (Parameter parameter : mergedParameters(definition)) {
            if (parameter == null || parameter.getName() == null || parameter.getIn() == null) {
                models.warn("Skipped unresolved parameter in " + operationKey(definition));
                continue;
            }
            String location = parameter.getIn().toLowerCase(Locale.ROOT);
            if (!Set.of("path", "query", "header", "cookie").contains(location)) {
                models.warn("Skipped unsupported parameter location '" + location + "' in " + operationKey(definition));
                continue;
            }
            boolean required = "path".equals(location) || Boolean.TRUE.equals(parameter.getRequired());
            JavaType type = models.typeFor(
                    parameter.getSchema(),
                    JavaNames.type(methodName) + JavaNames.type(parameter.getName()),
                    required,
                    false);
            String parameterName = unique(JavaNames.member(parameter.getName()), parameterNames);
            parameters.add(new MethodParameter(
                    parameterName,
                    parameter.getName(),
                    location,
                    required,
                    type));
        }

        RequestSchema request = requestSchema(operation);
        if (request != null) {
            boolean required = operation.getRequestBody() != null
                    && Boolean.TRUE.equals(operation.getRequestBody().getRequired());
            JavaType requestType = models.typeFor(
                    request.schema(),
                    JavaNames.type(methodName) + "Request",
                    required,
                    false);
            parameters.add(new MethodParameter(
                    unique("request", parameterNames),
                    "request",
                    "body",
                    required,
                    requestType));
        }

        boolean acceptsJson = responseContentTypes(operation).stream().anyMatch(this::isJson);
        return new ClientMethod(
                methodName,
                definition.method().name(),
                definition.path(),
                request == null ? null : request.contentType(),
                acceptsJson,
                Boolean.TRUE.equals(operation.getDeprecated()),
                parameters);
    }

    private String renderMethod(ClientMethod method) {
        StringBuilder source = new StringBuilder();
        source.append("\n");
        if (method.deprecated()) {
            source.append("    @Deprecated\n");
        }
        source.append("    public Response ").append(method.name()).append("(");
        if (method.parameters().isEmpty()) {
            source.append(") {\n");
        } else if (method.parameters().size() == 1) {
            MethodParameter parameter = method.parameters().getFirst();
            source.append(parameter.type().declaration()).append(" ").append(parameter.javaName()).append(") {\n");
        } else {
            source.append("\n");
            for (int index = 0; index < method.parameters().size(); index++) {
                MethodParameter parameter = method.parameters().get(index);
                source.append("            ")
                        .append(parameter.type().declaration())
                        .append(" ")
                        .append(parameter.javaName())
                        .append(index + 1 == method.parameters().size() ? ") {\n" : ",\n");
            }
        }

        source.append("        RequestSpecification requestSpec = api.request();\n");
        if (method.acceptsJson()) {
            source.append("        requestSpec.accept(\"application/json\");\n");
        }
        for (MethodParameter parameter : method.parameters()) {
            appendParameter(source, parameter, method.requestContentType());
        }
        source.append("        return ").append(httpCall(method)).append(";\n")
                .append("    }\n");
        return source.toString();
    }

    private void appendParameter(StringBuilder source, MethodParameter parameter, String requestContentType) {
        if ("body".equals(parameter.location())) {
            if (!parameter.required()) {
                source.append("        if (").append(parameter.javaName()).append(" != null) {\n");
            }
            String indent = parameter.required() ? "        " : "            ";
            if (requestContentType != null) {
                source.append(indent).append("requestSpec.contentType(\"")
                        .append(JavaNames.stringLiteral(requestContentType))
                        .append("\");\n");
            }
            source.append(indent).append("requestSpec.body(").append(parameter.javaName()).append(");\n");
            if (!parameter.required()) {
                source.append("        }\n");
            }
            return;
        }

        String call = switch (parameter.location()) {
            case "path" -> "pathParam";
            case "query" -> "queryParam";
            case "header" -> "header";
            case "cookie" -> "cookie";
            default -> throw new IllegalArgumentException("Unsupported parameter location " + parameter.location());
        };
        String statement = "requestSpec.%s(\"%s\", %s);".formatted(
                call,
                JavaNames.stringLiteral(parameter.wireName()),
                parameter.javaName());
        if (parameter.required()) {
            source.append("        ").append(statement).append('\n');
        } else {
            source.append("        if (").append(parameter.javaName()).append(" != null) {\n")
                    .append("            ").append(statement).append('\n')
                    .append("        }\n");
        }
    }

    private String httpCall(ClientMethod method) {
        String path = "\"" + JavaNames.stringLiteral(method.path()) + "\"";
        return switch (method.httpMethod()) {
            case "GET" -> "requestSpec.get(" + path + ")";
            case "POST" -> "requestSpec.post(" + path + ")";
            case "PUT" -> "requestSpec.put(" + path + ")";
            case "PATCH" -> "requestSpec.patch(" + path + ")";
            case "DELETE" -> "requestSpec.delete(" + path + ")";
            case "HEAD" -> "requestSpec.head(" + path + ")";
            case "OPTIONS" -> "requestSpec.options(" + path + ")";
            default -> "requestSpec.request(\""
                    + JavaNames.stringLiteral(method.httpMethod())
                    + "\", "
                    + path
                    + ")";
        };
    }

    private List<Parameter> mergedParameters(OperationDefinition definition) {
        Map<String, Parameter> merged = new LinkedHashMap<>();
        List<Parameter> pathParameters = definition.pathItem().getParameters();
        if (pathParameters != null) {
            pathParameters.forEach(parameter -> putParameter(merged, parameter));
        }
        if (definition.operation().getParameters() != null) {
            definition.operation().getParameters().forEach(parameter -> putParameter(merged, parameter));
        }
        return List.copyOf(merged.values());
    }

    private void putParameter(Map<String, Parameter> parameters, Parameter parameter) {
        if (parameter == null) {
            return;
        }
        parameters.put(parameter.getIn() + ":" + parameter.getName(), parameter);
    }

    private RequestSchema requestSchema(Operation operation) {
        RequestBody requestBody = operation.getRequestBody();
        if (requestBody == null || requestBody.getContent() == null) {
            return null;
        }
        Map.Entry<String, MediaType> selected = preferredContent(requestBody.getContent());
        if (selected == null || selected.getValue() == null || selected.getValue().getSchema() == null) {
            return null;
        }
        return new RequestSchema(selected.getKey(), selected.getValue().getSchema());
    }

    private List<String> responseContentTypes(Operation operation) {
        if (operation.getResponses() == null) {
            return List.of();
        }
        return operation.getResponses().values().stream()
                .filter(Objects::nonNull)
                .map(ApiResponse::getContent)
                .filter(Objects::nonNull)
                .flatMap(content -> content.keySet().stream())
                .distinct()
                .sorted()
                .toList();
    }

    private Map.Entry<String, MediaType> preferredContent(Content content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        return content.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .sorted(Comparator
                        .comparing((Map.Entry<String, MediaType> entry) -> !isJson(entry.getKey()))
                        .thenComparing(Map.Entry::getKey))
                .findFirst()
                .orElse(null);
    }

    private boolean isJson(String contentType) {
        String value = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return value.equals("application/json") || value.endsWith("+json");
    }

    private String tag(Operation operation) {
        if (operation.getTags() == null) {
            return "default";
        }
        return operation.getTags().stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .findFirst()
                .orElse("default");
    }

    private String operationKey(OperationDefinition definition) {
        return definition.method().name() + " " + definition.path();
    }

    private static String unique(String requested, Set<String> used) {
        String value = requested;
        int suffix = 2;
        while (!used.add(value)) {
            value = requested + suffix++;
        }
        return value;
    }

    private static String sourcePath(String packageName, String typeName) {
        return packageName.replace('.', '/') + "/" + typeName + ".java";
    }

    private record OperationDefinition(
            String path,
            PathItem.HttpMethod method,
            PathItem pathItem,
            Operation operation) {
    }

    private record RequestSchema(String contentType, Schema<?> schema) {
    }

    private record ClientMethod(
            String name,
            String httpMethod,
            String path,
            String requestContentType,
            boolean acceptsJson,
            boolean deprecated,
            List<MethodParameter> parameters) {

        boolean usesModel() {
            return parameters.stream().anyMatch(parameter -> parameter.type().model());
        }
    }

    private record MethodParameter(
            String javaName,
            String wireName,
            String location,
            boolean required,
            JavaType type) {
    }

    private record JavaType(String declaration, boolean model) {
    }

    private final class ModelRegistry {

        private final OpenAPI openApi;
        private final String modelPackage;
        private final Map<String, Schema<?>> models = new TreeMap<>();
        private final Map<Schema<?>, String> namesByIdentity = new IdentityHashMap<>();
        private final ArrayDeque<String> pending = new ArrayDeque<>();
        private final Set<String> warnings = new TreeSet<>();

        private ModelRegistry(OpenAPI openApi, String modelPackage) {
            this.openApi = openApi;
            this.modelPackage = modelPackage;
        }

        private void registerComponents() {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        if (isObject(entry.getValue())) {
                            register(JavaNames.type(entry.getKey()), entry.getValue());
                        } else if (composed(entry.getValue())) {
                            warn("Mapped oneOf/anyOf component " + entry.getKey() + " to Object when referenced");
                        }
                    });
        }

        private void registerOperationSchemas(OperationDefinition definition) {
            Operation operation = definition.operation();
            String operationName = operation.getOperationId();
            if (operationName == null || operationName.isBlank()) {
                operationName = definition.method().name().toLowerCase(Locale.ROOT) + " " + definition.path();
            }
            String operationType = JavaNames.type(operationName);

            for (Parameter parameter : mergedParameters(definition)) {
                if (parameter != null && parameter.getName() != null) {
                    registerInlineSchema(
                            parameter.getSchema(),
                            operationType + JavaNames.type(parameter.getName()));
                }
            }

            RequestSchema request = requestSchema(operation);
            if (request != null) {
                registerInlineSchema(request.schema(), operationType + "Request");
            }
            if (operation.getResponses() == null) {
                return;
            }
            operation.getResponses().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(response -> {
                        Content content = response.getValue() == null ? null : response.getValue().getContent();
                        Map.Entry<String, MediaType> selected = preferredContent(content);
                        Schema<?> schema = selected == null || selected.getValue() == null
                                ? null
                                : selected.getValue().getSchema();
                        registerInlineSchema(
                                schema,
                                operationType + JavaNames.type(response.getKey()) + "Response");
                    });
        }

        private void discoverNestedModels() {
            while (!pending.isEmpty()) {
                String modelName = pending.removeFirst();
                Schema<?> schema = models.get(modelName);
                for (Map.Entry<String, Schema<?>> property : properties(schema).entrySet()) {
                    registerInlineSchema(
                            property.getValue(),
                            modelName + JavaNames.type(property.getKey()));
                }
            }
        }

        private void registerInlineSchema(Schema<?> schema, String suggestion) {
            if (schema == null || referenceName(schema) != null) {
                return;
            }
            if (composed(schema)) {
                warn("Mapped oneOf/anyOf schema " + suggestion + " to Object");
                return;
            }
            Schema<?> items = arrayItems(schema);
            if (items != null || schema instanceof ArraySchema || "array".equals(schema.getType())) {
                registerInlineSchema(items, suggestion + "Item");
                return;
            }
            if (isObject(schema) && !properties(schema).isEmpty()) {
                register(suggestion, schema);
                return;
            }
            if (schema.getAdditionalProperties() instanceof Schema<?> additionalSchema) {
                registerInlineSchema(additionalSchema, suggestion + "Value");
            }
        }

        private List<GeneratedSource> render() {
            List<GeneratedSource> sources = new ArrayList<>();
            models.forEach((name, schema) -> sources.add(renderModel(name, schema)));
            return List.copyOf(sources);
        }

        private GeneratedSource renderModel(String name, Schema<?> schema) {
            Set<String> required = required(schema);
            Set<String> usedFieldNames = new LinkedHashSet<>();
            List<ModelProperty> fields = new ArrayList<>();
            properties(schema).entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        String javaName = unique(JavaNames.member(entry.getKey()), usedFieldNames);
                        fields.add(new ModelProperty(
                                entry.getKey(),
                                javaName,
                                !javaName.equals(entry.getKey()),
                                typeFor(
                                        entry.getValue(),
                                        name + JavaNames.type(entry.getKey()),
                                        required.contains(entry.getKey()),
                                        false)));
                    });

            StringBuilder source = new StringBuilder();
            source.append("package ").append(modelPackage).append(";\n");
            if (fields.stream().anyMatch(ModelProperty::jsonProperty)) {
                source.append("\nimport com.fasterxml.jackson.annotation.JsonProperty;\n");
            }
            source.append("\n/** Generated from OpenAPI. Regenerate instead of editing this file. */\n")
                    .append("public record ").append(name).append("(");
            if (fields.isEmpty()) {
                source.append(") {\n}\n");
            } else {
                source.append("\n");
                for (int index = 0; index < fields.size(); index++) {
                    ModelProperty field = fields.get(index);
                    source.append("        ");
                    if (field.jsonProperty()) {
                        source.append("@JsonProperty(\"")
                                .append(JavaNames.stringLiteral(field.wireName()))
                                .append("\") ");
                    }
                    source.append(field.type().declaration())
                            .append(" ")
                            .append(field.javaName())
                            .append(index + 1 == fields.size() ? ") {\n}\n" : ",\n");
                }
            }
            return new GeneratedSource(sourcePath(modelPackage, name), source.toString());
        }

        private JavaType typeFor(Schema<?> schema, String suggestion, boolean required, boolean generic) {
            if (schema == null) {
                return new JavaType("Object", false);
            }
            String referenceName = referenceName(schema);
            if (referenceName != null) {
                Schema<?> referenced = component(referenceName);
                if (referenced != null && !isObject(referenced)) {
                    return typeFor(referenced, suggestion, required, generic);
                }
                String modelName = register(JavaNames.type(referenceName), referenced == null ? schema : referenced);
                return new JavaType(modelName, true);
            }
            String knownName = namesByIdentity.get(schema);
            if (knownName != null) {
                return new JavaType(knownName, true);
            }
            if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()
                    || schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
                warn("Mapped oneOf/anyOf schema " + suggestion + " to Object");
                return new JavaType("Object", false);
            }
            if (arrayItems(schema) != null || schema instanceof ArraySchema || "array".equals(schema.getType())) {
                JavaType itemType = typeFor(arrayItems(schema), suggestion + "Item", false, true);
                return new JavaType("java.util.List<" + boxed(itemType.declaration()) + ">", itemType.model());
            }
            if (isObject(schema)) {
                if (!properties(schema).isEmpty()) {
                    return new JavaType(register(suggestion, schema), true);
                }
                if (schema.getAdditionalProperties() instanceof Schema<?> valueSchema) {
                    JavaType valueType = typeFor(valueSchema, suggestion + "Value", false, true);
                    return new JavaType(
                            "java.util.Map<String, " + boxed(valueType.declaration()) + ">",
                            valueType.model());
                }
                return new JavaType("java.util.Map<String, Object>", false);
            }

            boolean primitive = required && !Boolean.TRUE.equals(schema.getNullable()) && !generic;
            return switch (schema.getType() == null ? "" : schema.getType()) {
                case "integer" -> new JavaType(integerType(schema.getFormat(), primitive), false);
                case "number" -> new JavaType(numberType(schema.getFormat(), primitive), false);
                case "boolean" -> new JavaType(primitive ? "boolean" : "Boolean", false);
                case "string" -> new JavaType(stringType(schema.getFormat()), false);
                default -> new JavaType("Object", false);
            };
        }

        private String register(String requestedName, Schema<?> schema) {
            if (schema == null) {
                return JavaNames.type(requestedName);
            }
            String existing = namesByIdentity.get(schema);
            if (existing != null) {
                return existing;
            }
            String base = JavaNames.type(requestedName);
            String name = base;
            int suffix = 2;
            while (models.containsKey(name) && models.get(name) != schema) {
                name = base + suffix++;
            }
            if (!models.containsKey(name)) {
                models.put(name, schema);
                pending.addLast(name);
            }
            namesByIdentity.put(schema, name);
            return name;
        }

        private Map<String, Schema<?>> properties(Schema<?> schema) {
            Map<String, Schema<?>> properties = new TreeMap<>();
            collectProperties(schema, properties, Collections.newSetFromMap(new IdentityHashMap<>()));
            return properties;
        }

        @SuppressWarnings("unchecked")
        private void collectProperties(
                Schema<?> schema,
                Map<String, Schema<?>> target,
                Set<Schema<?>> visited) {
            if (schema == null || !visited.add(schema)) {
                return;
            }
            String reference = referenceName(schema);
            if (reference != null) {
                collectProperties(component(reference), target, visited);
            }
            if (schema.getAllOf() != null) {
                schema.getAllOf().forEach(part -> collectProperties(part, target, visited));
            }
            if (schema.getProperties() != null) {
                ((Map<String, Schema<?>>) (Map<?, ?>) schema.getProperties()).forEach(target::put);
            }
        }

        private Set<String> required(Schema<?> schema) {
            Set<String> required = new LinkedHashSet<>();
            collectRequired(schema, required, Collections.newSetFromMap(new IdentityHashMap<>()));
            return Set.copyOf(required);
        }

        private void collectRequired(Schema<?> schema, Set<String> target, Set<Schema<?>> visited) {
            if (schema == null || !visited.add(schema)) {
                return;
            }
            String reference = referenceName(schema);
            if (reference != null) {
                collectRequired(component(reference), target, visited);
            }
            if (schema.getAllOf() != null) {
                schema.getAllOf().forEach(part -> collectRequired(part, target, visited));
            }
            if (schema.getRequired() != null) {
                target.addAll(schema.getRequired());
            }
        }

        private Schema<?> component(String name) {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return null;
            }
            return openApi.getComponents().getSchemas().get(name);
        }

        private String referenceName(Schema<?> schema) {
            if (schema == null || schema.get$ref() == null || schema.get$ref().isBlank()) {
                return null;
            }
            String reference = schema.get$ref();
            int slash = reference.lastIndexOf('/');
            return slash >= 0 ? reference.substring(slash + 1) : reference;
        }

        private boolean isObject(Schema<?> schema) {
            return schema != null && ("object".equals(schema.getType())
                    || schema.getProperties() != null && !schema.getProperties().isEmpty()
                    || schema.getAllOf() != null && !schema.getAllOf().isEmpty()
                    || referenceName(schema) != null && isObject(component(referenceName(schema))));
        }

        private boolean composed(Schema<?> schema) {
            return schema != null && (schema.getOneOf() != null && !schema.getOneOf().isEmpty()
                    || schema.getAnyOf() != null && !schema.getAnyOf().isEmpty());
        }

        private Schema<?> arrayItems(Schema<?> schema) {
            if (schema == null) {
                return null;
            }
            if (schema instanceof ArraySchema arraySchema) {
                return arraySchema.getItems();
            }
            return schema.getItems();
        }

        private int size() {
            return models.size();
        }

        private void warn(String warning) {
            warnings.add(warning);
        }

        private String integerType(String format, boolean primitive) {
            if ("int64".equals(format)) {
                return primitive ? "long" : "Long";
            }
            return primitive ? "int" : "Integer";
        }

        private String numberType(String format, boolean primitive) {
            if ("float".equals(format)) {
                return primitive ? "float" : "Float";
            }
            if ("double".equals(format)) {
                return primitive ? "double" : "Double";
            }
            return "java.math.BigDecimal";
        }

        private String stringType(String format) {
            return switch (format == null ? "" : format) {
                case "date" -> "java.time.LocalDate";
                case "date-time" -> "java.time.OffsetDateTime";
                case "uuid" -> "java.util.UUID";
                case "binary", "byte" -> "byte[]";
                default -> "String";
            };
        }

        private String boxed(String declaration) {
            return switch (declaration) {
                case "int" -> "Integer";
                case "long" -> "Long";
                case "float" -> "Float";
                case "double" -> "Double";
                case "boolean" -> "Boolean";
                default -> declaration;
            };
        }
    }

    private record ModelProperty(
            String wireName,
            String javaName,
            boolean jsonProperty,
            JavaType type) {
    }
}
