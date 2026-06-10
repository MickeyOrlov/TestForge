package io.testforge.contract.json;

import java.util.List;

public class ContractValidationException extends RuntimeException {

    private final List<ContractViolation> violations;

    public ContractValidationException(List<ContractViolation> violations) {
        super("Contract validation failed: " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<ContractViolation> violations() {
        return violations;
    }
}
