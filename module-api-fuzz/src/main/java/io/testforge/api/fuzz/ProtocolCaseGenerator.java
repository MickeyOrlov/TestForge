package io.testforge.api.fuzz;

import io.testforge.api.explorer.ExplorableOperation;
import java.util.ArrayList;
import java.util.List;

/**
 * Cases that break the request envelope rather than a value inside it.
 *
 * <p>These reach a completely different layer of a service. A schema mutation is
 * judged by validation code the team wrote; a protocol mutation is judged by the
 * framework's body parser, its content negotiation, its error handler — code
 * nobody on the team wrote and few have read. That is exactly where a stack
 * trace tends to escape into a response body, and it is why these cases earn
 * their place even though they exercise no declared constraint.
 *
 * <p>Which is also why they are counted separately. Folding four protocol cases
 * into constraint coverage would report an operation as better tested for work
 * that touched none of its promises.
 *
 * <p>Only two of the four can claim a {@code REJECT}. Broken JSON cannot be an
 * instance of any schema, and a media type the operation does not list is not
 * described by the document at all. A missing {@code Content-Type} is weaker —
 * a recipient is entitled to guess — so it goes out as a probe, where only a
 * crash counts.
 */
public class ProtocolCaseGenerator {

    static final String MALFORMED_JSON = "{\"testforge\": ";
    static final String FOREIGN_CONTENT_TYPE = "text/plain";

    public List<FuzzCase> generate(ExplorableOperation operation, BodyPlan bodyPlan) {
        if (!bodyPlan.usable()) {
            // without a valid baseline body there is nothing to malform, and a
            // guessed one would make the case about this module's guess
            return List.of();
        }

        List<FuzzCase> cases = new ArrayList<>();
        cases.add(FuzzCase.protocol(operation.specId(), operation.operationId(), operation.key(),
                FuzzCaseKind.MALFORMED_JSON, FuzzExpectation.REJECT, MALFORMED_JSON));

        if (!bodyPlan.declaredContentTypes().contains(FOREIGN_CONTENT_TYPE)) {
            cases.add(FuzzCase.protocol(operation.specId(), operation.operationId(), operation.key(),
                    FuzzCaseKind.UNSUPPORTED_CONTENT_TYPE, FuzzExpectation.REJECT, FOREIGN_CONTENT_TYPE));
        }

        cases.add(FuzzCase.protocol(operation.specId(), operation.operationId(), operation.key(),
                FuzzCaseKind.MISSING_CONTENT_TYPE, FuzzExpectation.UNSPECIFIED, null));

        if (bodyPlan.required()) {
            // only a required body makes an empty one a broken promise
            cases.add(FuzzCase.protocol(operation.specId(), operation.operationId(), operation.key(),
                    FuzzCaseKind.EMPTY_BODY, FuzzExpectation.REJECT, ""));
        }

        return List.copyOf(cases);
    }
}
