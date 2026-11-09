// Native Spectral on owned documents: a complete lint writes fingerprinted SARIF; a remote $ref or a
// document that is not OpenAPI leaves no report and exit code 2. No network is used.
import {mkdtemp, writeFile, readFile, readdir, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
import {run, SPECTRAL_VERSION} from './run.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const work = await mkdtemp(join(tmpdir(), 'testforge-spectral-acceptance-'));
try {
  const linted = await run({output: join(work, 'linted'), specs: [
    join(root, 'example-tests/src/test/resources/openapi/demo-api.yaml'),
    join(root, 'example-tests/src/test/resources/openapi/access-demo.yaml')]});
  assert.equal(linted.exitCode, 0, JSON.stringify(linted.documents));
  for (const document of linted.documents) {
    const report = JSON.parse(await readFile(join(root, document.report), 'utf8'));
    const [first] = report.runs;
    assert.equal(first.tool.driver.name, 'spectral');
    assert.equal(first.tool.driver.version, SPECTRAL_VERSION);
    assert.ok(first.results.length > 0, 'the example API has OWASP findings');
    assert.ok(first.results.every(r => r.ruleId.startsWith('owasp:') && r.partialFingerprints['documentPath/v1']));
    assert.ok(first.results.every(r => !r.locations[0].physicalLocation.artifactLocation.uri.startsWith('/')),
      'locations are repository-relative');
  }

  const remote = join(work, 'remote.yaml');
  await writeFile(remote, 'openapi: 3.0.3\ninfo: {title: remote, version: "1"}\npaths:\n  /a:\n    get:\n'
    + '      responses:\n        "200":\n          description: ok\n          content:\n            application/json:\n'
    + '              schema: {$ref: "http://127.0.0.1:9/schema.json"}\n');
  const notOpenApi = join(work, 'notes.yaml');
  await writeFile(notOpenApi, 'title: not an API description\n');
  const refused = await run({output: join(work, 'refused'), specs: [remote, notOpenApi]});
  assert.equal(refused.exitCode, 2);
  assert.deepEqual(refused.documents.map(d => d.reason),
    ['incomplete lint: invalid-ref', 'incomplete lint: unrecognized-format']);
  assert.deepEqual(await readdir(join(work, 'refused')), [], 'an incomplete lint leaves no report');
  console.log(JSON.stringify(linted.documents));
} finally { await rm(work, {recursive: true, force: true}); }
