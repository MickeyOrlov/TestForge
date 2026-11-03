# module-mail: working on this module

Read root AGENTS.md and README.md. Production module may depend on core only.
- Mailpit owns SMTP, MIME, search and storage. Never implement these here.
- Bean construction/opening scopes are inert. Only explicit operations call the selected API.
- Keep UUID recipient isolation; verify exclusive To and empty Cc/Bcc before fetching attachments or deleting.
- Never send an empty DELETE list or use purge, release, forwarding or send endpoints.
- Preserve deadlines, response/message limits, no redirects and sanitized failures.
- Content is for explicit in-memory assertions; evidence contains only counts, opaque scope IDs and lifecycle.
- Cleanup is retryable; late delivery and server retention are documented limits.
Verify module/architecture tests, native MailpitIT, full build, published consumer and module removal.
