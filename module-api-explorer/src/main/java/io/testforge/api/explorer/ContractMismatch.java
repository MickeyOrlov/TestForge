package io.testforge.api.explorer;

/**
 * One difference between what came back and what the document declares.
 *
 * <p>{@code location} is a JSON path for body findings ({@code $.items[].id})
 * and {@code $} for whole-response findings such as an undocumented status, so
 * a reader always knows where to look.
 */
public record ContractMismatch(MismatchKind kind, String location, String detail) {

    public static ContractMismatch at(MismatchKind kind, String location, String detail) {
        return new ContractMismatch(kind, location, detail);
    }

    public static ContractMismatch response(MismatchKind kind, String detail) {
        return new ContractMismatch(kind, "$", detail);
    }
}
