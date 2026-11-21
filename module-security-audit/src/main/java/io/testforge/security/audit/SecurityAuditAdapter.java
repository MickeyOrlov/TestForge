package io.testforge.security.audit;

import io.testforge.security.audit.SecurityAuditProfile.Engine;

/** Trusted application integration code, registered explicitly; never loaded from profile data. */
public interface SecurityAuditAdapter {
    Engine engine();

    String version();

    /** Must be bounded/interruptible and prepare only; the executor alone launches the command. */
    SecurityAuditCommand prepare(SecurityAuditExecutionContext context) throws Exception;
}
