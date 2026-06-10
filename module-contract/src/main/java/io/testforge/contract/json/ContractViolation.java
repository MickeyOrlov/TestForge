package io.testforge.contract.json;

public record ContractViolation(String contract, String path, String code, String message) {
}
