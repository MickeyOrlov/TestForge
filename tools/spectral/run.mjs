// Lints local OpenAPI documents with native Spectral and the OWASP API Security Top 10 ruleset, one
// SARIF 2.1.0 report per document, for module-quality's static-analysis dimension to judge.
import {spawn} from 'node:child_process';
import {readFile, writeFile, mkdir, rm, readdir, rename, stat} from 'node:fs/promises';
import {resolve, dirname, join, basename, relative} from 'node:path';
import {fileURLToPath} from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const cli = join(here, 'node_modules/@stoplight/spectral-cli/dist/index.js');
export const SPECTRAL_VERSION = '6.17.0';
export const RULESET_VERSION = '2.0.1';

/** A refusal whose message names only the rule broken, never document content: safe to print. */
export class WorkflowError extends Error {}
const refuse = message => { throw new WorkflowError(message); };

// Results that mean the OWASP rules did not see the whole document: a lint with one is incomplete,
// and an incomplete lint must not reach the quality gate as if it had found everything.
export const INCOMPLETE = ['invalid-ref', 'unrecognized-format', 'parser'];

const INHERITED = ['PATH', 'HOME', 'TMPDIR', 'TEMP', 'TMP', 'SystemRoot', 'WINDIR', 'LANG'];
export function childEnvironment(source = process.env) {
  return Object.fromEntries(Object.entries(source).filter(([key]) => INHERITED.includes(key)));
}

/** 1..50 existing local .yaml/.yml/.json files; never a URL or a glob. */
export async function validate(specs) {
  if (!Array.isArray(specs) || specs.length < 1 || specs.length > 50) refuse('Select 1..50 OpenAPI documents');
  for (const spec of specs) {
    if (typeof spec !== 'string' || /^[a-z][a-z0-9+.-]*:\/\//i.test(spec) || /[*?{}[\]]/.test(spec))
      refuse('Select local OpenAPI files, not URLs or globs');
    if (!/\.(ya?ml|json)$/i.test(spec)) refuse('OpenAPI documents must be .yaml, .yml or .json files');
    let info;
    try { info = await stat(spec); } catch { refuse(`No OpenAPI document at ${spec}`); }
    if (!info.isFile()) refuse(`No OpenAPI document at ${spec}`);
  }
}

/** The locked packages must be the pinned ones, or the report would name versions that did not run. */
export async function installedVersions(modules = join(here, 'node_modules')) {
  const version = async name => {
    try { return JSON.parse(await readFile(join(modules, name, 'package.json'), 'utf8')).version; } catch { return undefined; }
  };
  const spectral = await version('@stoplight/spectral-cli');
  const ruleset = await version('@stoplight/spectral-owasp-ruleset');
  if (spectral !== SPECTRAL_VERSION || ruleset !== RULESET_VERSION)
    refuse(`Installed @stoplight/spectral-cli ${spectral ?? 'missing'} / spectral-owasp-ruleset ${ruleset ?? 'missing'}; `
      + `this workflow is pinned to ${SPECTRAL_VERSION} / ${RULESET_VERSION} (run npm ci in tools/spectral)`);
  return {spectral, ruleset};
}

/** Report file names: the document's base name, numbered when two documents share one. */
export function reportNames(specs) {
  const used = new Map();
  return specs.map(spec => {
    const stem = basename(spec).replace(/\.(ya?ml|json)$/i, '').replace(/[^A-Za-z0-9_.-]/g, '_');
    const n = (used.get(stem) ?? 0) + 1;
    used.set(stem, n);
    return `${stem}${n > 1 ? `-${n}` : ''}.sarif`;
  });
}

/**
 * Spectral's SARIF has no fingerprints, so module-quality would key every result of one rule in one
 * document as a single issue: a newly unprotected operation would hide behind an old one. The same
 * lint's JSON output carries each result's document path ({@code paths./orders.get}), which survives
 * moved lines; it becomes the result's partial fingerprint. The two outputs list results in the same
 * order; any mismatch returns {@code false} and the lint counts as failed.
 */
export function fingerprint(sarifResults, jsonResults) {
  if (!Array.isArray(jsonResults) || jsonResults.length !== sarifResults.length) return false;
  for (const [i, result] of sarifResults.entries()) {
    const native = jsonResults[i];
    const line = result.locations?.[0]?.physicalLocation?.region?.startLine;
    if (native?.code !== result.ruleId || native.range?.start?.line + 1 !== line || !Array.isArray(native.path)) return false;
    result.partialFingerprints = {...result.partialFingerprints, 'documentPath/v1': native.path.join('.')};
  }
  return true;
}

function lint(spec, sarif, json, cwd, timeout) {
  return new Promise(done => {
    const child = spawn(process.execPath, [cli, 'lint', spec, '--ruleset', join(here, 'ruleset.mjs'),
      '--resolver', join(here, 'resolver.cjs'), '--format', 'sarif', '--output.sarif', sarif,
      '--format', 'json', '--output.json', json, '--quiet', '--fail-severity', 'error'],
    {cwd, env: childEnvironment(), stdio: 'ignore'});
    const timer = setTimeout(() => child.kill('SIGKILL'), timeout);
    child.once('error', () => { clearTimeout(timer); done(null); });
    child.once('close', code => { clearTimeout(timer); done(code); });
  });
}

/**
 * Lints each document into {@code <output>/<name>.sarif}. Exit code 0 when every document was
 * linted completely — findings or not: judging them is module-quality's job — and 2 otherwise. A
 * document whose lint failed or was incomplete gets no report, so the gate cannot read a partial
 * lint as a complete one.
 */
export async function run({specs, output = resolve(here, '../../build/spectral'), root = resolve(here, '../..'), timeout = 120000}) {
  await validate(specs);
  const versions = await installedVersions();
  const dir = resolve(output);
  await mkdir(dir, {recursive: true});
  // reports of an earlier lint in this directory would be judged as this one's
  for (const file of await readdir(dir)) if (file.endsWith('.sarif')) await rm(join(dir, file));
  const names = reportNames(specs);
  const documents = [];
  for (const [i, spec] of specs.entries()) {
    const absolute = resolve(spec);
    const target = join(dir, names[i]);
    const partial = `${target}.partial`;
    const native = `${target}.native.json`;
    // run from the repository root, so SARIF locations are repository-relative paths
    const code = await lint(relative(root, absolute), partial, native, root, timeout);
    let report, nativeResults;
    try { report = JSON.parse(await readFile(partial, 'utf8')); } catch { /* no or invalid report */ }
    try { nativeResults = JSON.parse(await readFile(native, 'utf8')); } catch { /* no or invalid output */ }
    await rm(native, {force: true});
    const first = report?.version === '2.1.0' && Array.isArray(report.runs) ? report.runs[0] : undefined;
    const results = Array.isArray(first?.results) ? first.results : undefined;
    const incomplete = results?.filter(r => INCOMPLETE.includes(r.ruleId)).map(r => r.ruleId) ?? [];
    const reason = ![0, 1].includes(code) || !results ? `spectral exit ${code}, no valid SARIF`
      : incomplete.length ? `incomplete lint: ${[...new Set(incomplete)].join(', ')}`
        : !fingerprint(results, nativeResults) ? 'SARIF and JSON outputs disagree' : null;
    if (reason) {
      await rm(partial, {force: true});
      documents.push({spec: relative(root, absolute), report: null, reason});
      continue;
    }
    // Spectral 6.17.0 still writes its stale VERSION constant (6.16.3); the report names what ran
    first.tool.driver.version = versions.spectral;
    first.tool.driver.properties = {...first.tool.driver.properties,
      ruleset: `@stoplight/spectral-owasp-ruleset ${versions.ruleset}`};
    await writeFile(partial, JSON.stringify(report, null, 2));
    await rename(partial, target);
    documents.push({spec: relative(root, absolute), report: relative(root, target), results: results.length});
  }
  return {exitCode: documents.every(d => d.report) ? 0 : 2, dir, versions, documents};
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    let output;
    const at = args.indexOf('--output');
    if (at >= 0) {
      output = args[at + 1];
      if (!output) refuse('--output needs a directory');
      args.splice(at, 2);
    }
    const result = await run({specs: args, output});
    for (const d of result.documents)
      console.log(d.report ? `${d.spec}: ${d.results} results -> ${d.report}` : `${d.spec}: NOT LINTED (${d.reason})`);
    process.exitCode = result.exitCode;
  } catch (error) {
    console.error(error instanceof WorkflowError ? `Spectral workflow refused: ${error.message}`
      : 'Spectral workflow execution failed; inspect trusted local inputs');
    process.exitCode = 2;
  }
}
