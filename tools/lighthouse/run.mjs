import {spawn} from 'node:child_process';
import {readFile, writeFile, mkdir, copyFile} from 'node:fs/promises';
import {resolve, dirname, join, isAbsolute} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash, randomUUID} from 'node:crypto';
const here = dirname(fileURLToPath(import.meta.url));
const cli = join(here, 'node_modules/@lhci/cli/src/cli.js');
const group = join(here, 'group.mjs');
export const LHCI_VERSION = '0.16.0';
const object = value => value && typeof value === 'object' && !Array.isArray(value);
const keys = (value, allowed) => object(value) && Object.keys(value).every(key => allowed.includes(key));

/** A refusal whose message names only the rule, never input content: safe to print. */
export class WorkflowError extends Error {}
const refuse = message => { throw new WorkflowError(message); };

// Proxy and CA settings reach Chrome so targets behind a corporate proxy or internal CA stay
// reachable; tokens, LHCI_* options and NODE_OPTIONS never do.
const INHERITED = ['PATH', 'HOME', 'TMPDIR', 'TEMP', 'TMP', 'SystemRoot', 'WINDIR', 'LANG',
  'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'no_proxy',
  'NODE_EXTRA_CA_CERTS', 'SSL_CERT_FILE', 'SSL_CERT_DIR'];
export function childEnvironment(source = process.env) {
  return Object.fromEntries(Object.entries(source).filter(([key]) => INHERITED.includes(key)));
}
export function validate(config, urls, chrome) {
  if (!keys(config, ['ci']) || !keys(config.ci, ['collect', 'assert']) ||
      !keys(config.ci.collect, ['numberOfRuns', 'settings']) ||
      !keys(config.ci.assert, ['assertions', 'budgetsFile']) || !chrome || !isAbsolute(chrome))
    refuse('Use native JSON ci.collect/ci.assert and an explicit absolute Chrome executable');
  const runs = config.ci.collect.numberOfRuns ?? 3;
  if (!Number.isInteger(runs) || runs < 1 || runs > 10 ||
      (config.ci.collect.settings !== undefined && !object(config.ci.collect.settings)))
    refuse('Select 1..10 runs and native settings');
  const checks = config.ci.assert;
  if (Boolean(checks.budgetsFile) === Boolean(checks.assertions) ||
      checks.assertions && (!object(checks.assertions) || !Object.values(checks.assertions).some(v =>
        v === 'error' || Array.isArray(v) && v[0] === 'error')))
    refuse('Select native budgetsFile or assertions with at least one error-level check');
  if (!Array.isArray(urls) || urls.length < 1 || urls.length > 20) refuse('Select 1..20 explicit URLs');
  for (const value of urls) {
    let url; try { url = new URL(value); } catch { refuse('Invalid target URL'); }
    if (typeof value !== 'string' || !['http:', 'https:'].includes(url.protocol) ||
        url.username || url.password || url.search || url.hash) refuse('Use HTTP(S) targets without credentials/query/fragment');
  }
}

/** The installed @lhci/cli must be the pinned one, or the evidence would name a version that did not run. */
export async function installedVersion(packageJson = join(here, 'node_modules/@lhci/cli/package.json')) {
  let version;
  try { version = JSON.parse(await readFile(packageJson, 'utf8')).version; } catch { /* not installed */ }
  if (version !== LHCI_VERSION)
    refuse(`${version ? `@lhci/cli ${version} is installed` : '@lhci/cli is not installed'}; `
      + `this workflow is pinned to ${LHCI_VERSION} (run npm ci in tools/lighthouse)`);
  return version;
}

/**
 * One native LHCI stage in its own process group, led by group.mjs. Timeouts and interrupts kill the
 * group while its leader is alive; the leader itself ends what is left once the command exits.
 */
function stage(command, cwd, config, timeout, active) {
  return new Promise(resolveStage => {
    const leader = spawn(process.execPath, [group, process.execPath, cli, command, '--config', config], {
      cwd, env: childEnvironment(), stdio: 'ignore', detached: true,
    });
    let timedOut = false;
    const kill = () => { try { process.kill(-leader.pid, 'SIGKILL'); } catch { /* already exited */ } };
    active.add(kill);
    const timer = setTimeout(() => { timedOut = true; kill(); }, timeout);
    const done = result => { clearTimeout(timer); active.delete(kill); resolveStage(result); };
    leader.once('error', () => done({code: null, error: true}));
    leader.once('close', (code, signal) => done({code, signal, timedOut}));
  });
}

/** Native assertion results as data: which page, which audit, the measured and the allowed value. */
const assertionFacts = results => results.map(r => ({url: r.url, auditId: r.auditId,
  ...(r.auditProperty ? {auditProperty: r.auditProperty} : {}), name: r.name, level: r.level,
  passed: r.passed, operator: r.operator, expected: r.expected, actual: r.actual}));

/**
 * Collects, asserts and exports with native LHCI. A library call: an interrupt kills the stages and
 * returns outcome INTERRUPTED; it never exits the calling process.
 */
export async function run({configPath, urlsPath, chrome, output = resolve(here, '../../build/lighthouse'), timeout = 180000}) {
  if (process.platform === 'win32') refuse('This workflow requires POSIX process groups');
  if (!configPath || !urlsPath || !Number.isInteger(timeout) || timeout < 1000 || timeout > 900000)
    refuse('Explicit config and URL list required; timeout 1000..900000ms');
  configPath = resolve(configPath); urlsPath = resolve(urlsPath);
  let config, urlBytes, urls;
  try {
    config = JSON.parse(await readFile(configPath, 'utf8'));
    urlBytes = await readFile(urlsPath);
    urls = JSON.parse(urlBytes.toString());
  } catch { refuse('Config and URL list must be readable JSON files'); }
  validate(config, urls, chrome);
  const version = await installedVersion();
  const dir = join(resolve(output), randomUUID()); await mkdir(dir, {recursive: true});
  if (config.ci.assert.budgetsFile) {
    const budgets = resolve(dirname(configPath), config.ci.assert.budgetsFile);
    let values;
    try { values = JSON.parse(await readFile(budgets, 'utf8')); } catch { refuse('Budgets file must be readable JSON'); }
    if (!Array.isArray(values) || !values.length) refuse('Empty native budgets');
    await copyFile(budgets, join(dir, 'budgets.json'));
    config.ci.assert.budgetsFile = join(dir, 'budgets.json');
  }
  config.ci.collect = {...config.ci.collect, url: urls, chromePath: chrome};
  config.ci.assert.includePassedAssertions = true;
  config.ci.upload = {target: 'filesystem', outputDir: join(dir, 'reports'),
    reportFilenamePattern: '%%HOSTNAME%%-%%PATHNAME%%-%%DATETIME%%.report.%%EXTENSION%%'};
  const snapshot = join(dir, 'lighthouserc.json');
  await writeFile(snapshot, JSON.stringify(config, null, 2));
  const summary = {formatVersion: 1, tool: '@lhci/cli', version, urls,
    urlListSha256: createHash('sha256').update(urlBytes).digest('hex'), stages: {}, outcome: 'ERROR'};

  const active = new Set();
  let interrupted = false;
  const interrupt = () => { interrupted = true; for (const kill of active) kill(); };
  process.on('SIGINT', interrupt); process.on('SIGTERM', interrupt);
  let exitCode = 2;
  try {
    const next = async name => {
      if (interrupted) return null;
      summary.stages[name] = await stage(name === 'export' ? 'upload' : name, dir, snapshot, timeout, active);
      return summary.stages[name];
    };
    const collect = await next('collect');
    if (collect?.code === 0) {
      const asserted = await next('assert');
      // Export even after native assertions fail. Never use autorun or public upload.
      const exported = await next('export');
      let results;
      try { results = JSON.parse(await readFile(join(dir, '.lighthouseci/assertion-results.json'), 'utf8')); } catch { /* invalid native result */ }
      if (Array.isArray(results)) summary.assertions = assertionFacts(results);
      const resultValid = asserted && exported && Array.isArray(results) && results.some(r => r.level === 'error') &&
        results.every(r => typeof r.passed === 'boolean') &&
        (asserted.code === 1) === results.some(r => r.level === 'error' && !r.passed);
      if (resultValid && exported.code === 0 && [0, 1].includes(asserted.code)) {
        exitCode = asserted.code;
        summary.outcome = exitCode === 0 ? 'PASSED' : 'ASSERTIONS_FAILED';
      }
    }
    if (Object.values(summary.stages).some(s => s.timedOut)) { summary.outcome = 'TIMED_OUT'; exitCode = 2; }
    if (interrupted) { summary.outcome = 'INTERRUPTED'; exitCode = 130; }
  } finally {
    process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt);
  }
  await writeFile(join(dir, 'summary.json'), JSON.stringify(summary, null, 2));
  return {exitCode, dir, summary};
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [configPath, urlsPath, chrome] = process.argv.slice(2);
    const result = await run({configPath, urlsPath, chrome});
    console.log(`${result.summary.outcome}: ${result.dir}`); process.exitCode = result.exitCode;
  } catch (error) {
    // Own refusals name the broken rule; anything else may quote input content, so it stays generic.
    console.error(error instanceof WorkflowError ? `Lighthouse workflow refused: ${error.message}`
      : 'Lighthouse workflow execution failed; inspect trusted local inputs');
    process.exitCode = 2;
  }
}
