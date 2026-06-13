package io.testforge.contract.monitor;

import io.testforge.contract.json.ContractViolation;
import io.testforge.contract.json.JsonContractValidator;
import io.testforge.contract.json.MessageContract;
import io.testforge.contract.json.SchemaContract;
import java.util.List;

public interface JsonPayloadContract {

    String name();

    List<ContractViolation> validate(JsonContractValidator validator, String json);

    static JsonPayloadContract of(MessageContract contract) {
        return new MessageContractAdapter(contract);
    }

    static JsonPayloadContract of(SchemaContract contract) {
        return new SchemaContractAdapter(contract);
    }

    record MessageContractAdapter(MessageContract contract) implements JsonPayloadContract {

        public MessageContractAdapter {
            if (contract == null) {
                throw new IllegalArgumentException("contract must not be null");
            }
        }

        @Override
        public String name() {
            return contract.name();
        }

        @Override
        public List<ContractViolation> validate(JsonContractValidator validator, String json) {
            return validator.validate(json, contract);
        }
    }

    record SchemaContractAdapter(SchemaContract contract) implements JsonPayloadContract {

        public SchemaContractAdapter {
            if (contract == null) {
                throw new IllegalArgumentException("contract must not be null");
            }
        }

        @Override
        public String name() {
            return contract.name();
        }

        @Override
        public List<ContractViolation> validate(JsonContractValidator validator, String json) {
            return validator.validate(json, contract);
        }
    }
}
