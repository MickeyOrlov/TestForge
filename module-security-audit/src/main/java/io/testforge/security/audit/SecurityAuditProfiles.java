package io.testforge.security.audit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Bounded strict JSON decoding; parser diagnostics cannot echo private profile data. */
public final class SecurityAuditProfiles {
    public static SecurityAuditProfile parse(String json) {
        if (json == null || json.length() > 262144)
            throw SecurityAuditProfile.invalid("audit profile JSON exceeds bounds");
        try {
            ObjectMapper mapper =
                    JsonMapper.builder(
                                    JsonFactory.builder()
                                            .streamReadConstraints(
                                                    StreamReadConstraints.builder()
                                                            .maxNestingDepth(32)
                                                            .maxStringLength(4096)
                                                            .maxNumberLength(10)
                                                            .build())
                                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                            .build())
                            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                            .build();
            var profile = mapper.readValue(json, SecurityAuditProfile.class);
            if (profile == null) throw new IllegalArgumentException();
            return profile;
        } catch (Exception e) {
            throw SecurityAuditProfile.invalid(
                    "audit profile JSON is invalid; inspect local configuration");
        }
    }

    private SecurityAuditProfiles() {}
}
