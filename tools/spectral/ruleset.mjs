// OWASP API Security Top 10 (2023) rules, loaded from the locked package — never fetched from a CDN.
// The rules are used as published. One change only: ruleset 2.0.1 ships
// owasp:api5:2023-admin-security-unique with an empty description, and Spectral's SARIF formatter
// then refuses to write the whole report (node-sarif-builder: SARIF_BUILDER_INVALID). A rule
// without a description gets its id as one.
import owasp from '@stoplight/spectral-owasp-ruleset';

const rules = Object.fromEntries(Object.entries(owasp.rules).map(([id, rule]) =>
  [id, rule.description ? rule : {...rule, description: id}]));

export default {...owasp, rules};
