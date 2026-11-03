import {spawn} from 'node:child_process';
import {readFile, writeFile, mkdir, copyFile} from 'node:fs/promises';
import {resolve, dirname, join, isAbsolute} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash, randomUUID} from 'node:crypto';
const here = dirname(fileURLToPath(import.meta.url));
const cli = join(here, 'node_modules/@lhci/cli/src/cli.js');
const object = value => value && typeof value === 'object' && !Array.isArray(value);
const keys = (value, allowed) => object(value) && Object.keys(value).every(key => allowed.includes(key));
export function childEnvironment(source = process.env) {
  return Object.fromEntries(Object.entries(source).filter(([key]) =>
    ['PATH', 'HOME', 'TMPDIR', 'TEMP', 'TMP', 'SystemRoot', 'WINDIR', 'LANG'].includes(key)));
}
export function validate(config, urls, chrome) {
  if (!keys(config, ['ci']) || !keys(config.ci, ['collect', 'assert']) ||
      !keys(config.ci.collect, ['numberOfRuns', 'settings']) ||
      !keys(config.ci.assert, ['assertions', 'budgetsFile']) || !chrome || !isAbsolute(chrome))
    throw Error('Use native JSON ci.collect/ci.assert and an explicit Chrome executable');
  const runs = config.ci.collect.numberOfRuns ?? 3;
  if (!Number.isInteger(runs) || runs < 1 || runs > 10 ||
      (config.ci.collect.settings !== undefined && !object(config.ci.collect.settings)))
    throw Error('Select 1..10 runs and native settings');
  const checks = config.ci.assert;
  if (Boolean(checks.budgetsFile) === Boolean(checks.assertions) ||
      checks.assertions && (!object(checks.assertions) || !Object.values(checks.assertions).some(v =>
        v === 'error' || Array.isArray(v) && v[0] === 'error')))
    throw Error('Select native budgetsFile or assertions with at least one error-level check');
  if (!Array.isArray(urls) || urls.length < 1 || urls.length > 20) throw Error('Select 1..20 explicit URLs');
  for (const value of urls) {
    let url; try { url = new URL(value); } catch { throw Error('Invalid target URL'); }
    if (typeof value !== 'string' || !['http:', 'https:'].includes(url.protocol) ||
        url.username || url.password || url.search || url.hash) throw Error('Use HTTP(S) targets without credentials/query/fragment');
  }
}
async function stage(command, cwd, config, timeout) {
  return new Promise(resolveStage => {
    const processChild = spawn(process.execPath, [cli, command, '--config', config], {
      cwd, env: childEnvironment(), stdio: 'ignore', detached: process.platform !== 'win32',
    });
    let timedOut = false;
    const kill = signal => {
      try { if (process.platform !== 'win32') process.kill(-processChild.pid, signal); else processChild.kill(signal); } catch { /* already exited */ }
    };
    const interrupt = () => { kill('SIGKILL'); process.exit(130); };
    process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
    const detach = () => { process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt); };
    const timer = setTimeout(() => { timedOut = true; kill('SIGKILL'); }, timeout);
    processChild.once('error', () => { clearTimeout(timer); detach(); resolveStage({code: null, error: true}); });
    processChild.once('close', (code, signal) => {
      clearTimeout(timer); detach(); kill('SIGKILL'); // Also reap Chrome descendants if native cleanup failed.
      resolveStage({code, signal, timedOut});
    });
  });
}
export async function run({configPath, urlsPath, chrome, output = resolve(here, '../../build/lighthouse'), timeout = 180000}) {
  if (process.platform === 'win32') throw Error('This workflow requires POSIX process groups');
  if (!configPath || !urlsPath || !chrome || !Number.isInteger(timeout) || timeout < 1000 || timeout > 900000)
    throw Error('Explicit config, URL list and Chrome path required; timeout 1000..900000ms');
  configPath = resolve(configPath); urlsPath = resolve(urlsPath);
  const config = JSON.parse(await readFile(configPath, 'utf8'));
  const urlBytes = await readFile(urlsPath);
  const urls = JSON.parse(urlBytes.toString());
  validate(config, urls, chrome);
  if (!isAbsolute(chrome)) throw Error('Chrome path must be absolute');
  const dir = join(resolve(output), randomUUID()); await mkdir(dir, {recursive: true});
  if (config.ci.assert.budgetsFile) {
    const budgets = resolve(dirname(configPath), config.ci.assert.budgetsFile);
    const values = JSON.parse(await readFile(budgets, 'utf8'));
    if (!Array.isArray(values) || !values.length) throw Error('Empty native budgets');
    await copyFile(budgets, join(dir, 'budgets.json'));
    config.ci.assert.budgetsFile = join(dir, 'budgets.json');
  }
  config.ci.collect = {...config.ci.collect, url: urls, chromePath: chrome};
  config.ci.assert.includePassedAssertions = true;
  config.ci.upload = {target: 'filesystem', outputDir: join(dir, 'reports'),
    reportFilenamePattern: '%%HOSTNAME%%-%%PATHNAME%%-%%DATETIME%%.report.%%EXTENSION%%'};
  const snapshot = join(dir, 'lighthouserc.json');
  await writeFile(snapshot, JSON.stringify(config, null, 2));
  const summary = {formatVersion: 1, tool: '@lhci/cli', version: '0.16.0',
    urlListSha256: createHash('sha256').update(urlBytes).digest('hex'), stages: {}, outcome: 'ERROR'};
  summary.stages.collect = await stage('collect', dir, snapshot, timeout);
  let exitCode = 2;
  if (summary.stages.collect.code === 0) {
    summary.stages.assert = await stage('assert', dir, snapshot, timeout);
    // Export even after native assertions fail. Never use autorun or public upload.
    summary.stages.export = await stage('upload', dir, snapshot, timeout);
    let results;
    try { results = JSON.parse(await readFile(join(dir, '.lighthouseci/assertion-results.json'), 'utf8')); } catch { /* invalid native result */ }
    const resultValid = Array.isArray(results) && results.some(r => r.level === 'error') &&
      results.every(r => typeof r.passed === 'boolean') &&
      (summary.stages.assert.code === 1) === results.some(r => r.level === 'error' && !r.passed);
    if (resultValid && summary.stages.export.code === 0 && [0, 1].includes(summary.stages.assert.code)) {
      exitCode = summary.stages.assert.code;
      summary.outcome = exitCode === 0 ? 'PASSED' : 'ASSERTIONS_FAILED';
    }
  }
  await writeFile(join(dir, 'summary.json'), JSON.stringify(summary, null, 2));
  return {exitCode, dir, summary};
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const [configPath, urlsPath, chrome] = process.argv.slice(2);
    const result = await run({configPath, urlsPath, chrome});
    console.log(`${result.summary.outcome}: ${result.dir}`); process.exitCode = result.exitCode;
  } catch { console.error('Lighthouse workflow configuration/execution failed; inspect trusted local inputs'); process.exitCode = 2; }
}
