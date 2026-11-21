package io.testforge.security;

import java.util.Locale;

/** Explicit header placement; key material comes only from a case-local binding. */
public record BusinessIdempotency(String header, String binding) {
    public BusinessIdempotency {
        if (header == null)
            throw new BusinessSecurityException("idempotency needs an explicit header");
        header =
                switch (header.toLowerCase(Locale.ROOT)) {
                    case "idempotency-key" -> "Idempotency-Key";
                    case "x-idempotency-key" -> "X-Idempotency-Key";
                    default ->
                            throw new BusinessSecurityException("unsupported idempotency header");
                };
        binding = BusinessSecuritySuite.identifier(binding);
    }
}
