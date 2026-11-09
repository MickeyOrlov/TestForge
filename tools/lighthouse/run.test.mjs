import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp, writeFile, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {validate, childEnvironment, installedVersion, LHCI_VERSION, WorkflowError} from './run.mjs';
const here = dirname(fileURLToPath(import.meta.url));
const config = () => ({ci: {collect: {numberOfRuns: 1}, assert: {assertions: {'first-contentful-paint': ['error', {maxNumericValue: 10000}]}}}});
test('targets and native assertions must be explicit; no server commands or public upload', () => {
  validate(config(), ['http://127.0.0.1:8000/'], '/chrome');
  for (const url of ['https://user:password@example.org/', 'https://example.org/?token=a', 'file:///etc/passwd'])
    assert.throws(() => validate(config(), [url], '/chrome'), WorkflowError);
  for (const change of [c => c.ci.upload = {target:'temporary-public-storage'},
    c => c.ci.collect.startServerCommand = 'echo secret', c => c.ci.assert.assertions = {},
    c => c.ci.assert.assertions = {'first-contentful-paint':'warn'}]) {
    const c = config(); change(c); assert.throws(() => validate(c, ['https://example.org/'], '/chrome'), WorkflowError);
  }
  assert.throws(() => validate(config(), ['https://example.org/'], 'chrome'), /absolute Chrome/);
});
test('native children do not inherit CI tokens, LHCI options or Node injection, but keep proxy and CA settings', () => {
  assert.deepEqual(childEnvironment({PATH:'/bin', HOME:'/tmp', NODE_OPTIONS:'--require evil',
    LHCI_TOKEN:'secret', GITHUB_TOKEN:'secret', AWS_SECRET_ACCESS_KEY:'secret',
    HTTPS_PROXY:'http://proxy:3128', NO_PROXY:'localhost', SSL_CERT_FILE:'/ca.pem', NODE_EXTRA_CA_CERTS:'/ca.pem'}),
  {PATH:'/bin', HOME:'/tmp', HTTPS_PROXY:'http://proxy:3128', NO_PROXY:'localhost', SSL_CERT_FILE:'/ca.pem',
    NODE_EXTRA_CA_CERTS:'/ca.pem'});
});
test('a different or missing @lhci/cli is refused instead of being reported as the pinned version', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'testforge-lhci-version-'));
  try {
    const file = join(dir, 'package.json');
    await writeFile(file, JSON.stringify({version: '0.15.1'}));
    await assert.rejects(installedVersion(file), /0\.15\.1 is installed.*pinned to 0\.16\.0/);
    await assert.rejects(installedVersion(join(dir, 'absent.json')), /not installed/);
    await writeFile(file, JSON.stringify({version: LHCI_VERSION}));
    assert.equal(await installedVersion(file), LHCI_VERSION);
  } finally { await rm(dir, {recursive: true, force: true}); }
});
test('the group leader reports the exit code and ends descendants left behind', {skip: process.platform === 'win32'}, async () => {
  // the command leaves a long-lived grandchild in the group, records its pid and exits 3
  const script = "const c=require('node:child_process').spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});"
    + "require('node:fs').writeFileSync(process.argv[1],String(c.pid));process.exit(3)";
  const dir = await mkdtemp(join(tmpdir(), 'testforge-lhci-group-'));
  try {
    const pidFile = join(dir, 'pid');
    const code = await new Promise(resolve => spawn(process.execPath,
      [join(here, 'group.mjs'), process.execPath, '-e', script, pidFile], {stdio: 'ignore', detached: true})
      .once('close', resolve));
    assert.equal(code, 3);
    const pid = Number(await readFile(pidFile, 'utf8'));
    const deadline = Date.now() + 2000;
    let alive = true;
    while (alive && Date.now() < deadline) {
      try { process.kill(pid, 0); await new Promise(next => setImmediate(next)); } catch { alive = false; }
    }
    assert.equal(alive, false, 'grandchild must not outlive its stage');
  } finally { await rm(dir, {recursive: true, force: true}); }
});
