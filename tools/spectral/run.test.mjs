import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, writeFile, mkdir, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {validate, reportNames, fingerprint, installedVersions, childEnvironment, WorkflowError,
  SPECTRAL_VERSION, RULESET_VERSION} from './run.mjs';

test('only existing local OpenAPI files are linted: no URLs, globs or other files', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'testforge-spectral-'));
  try {
    const spec = join(dir, 'api.yaml');
    await writeFile(spec, 'openapi: 3.0.3\n');
    await validate([spec]);
    for (const bad of [[], ['https://example.org/api.yaml'], [join(dir, '*.yaml')], [join(dir, 'api.txt')],
      [join(dir, 'absent.yaml')], [dir + '/']])
      await assert.rejects(validate(bad), WorkflowError);
  } finally { await rm(dir, {recursive: true, force: true}); }
});

test('report names follow the document and stay distinct', () => {
  assert.deepEqual(reportNames(['a/openapi.yaml', 'b/openapi.json', 'c/my api.yml']),
    ['openapi.sarif', 'openapi-2.sarif', 'my_api.sarif']);
});

test('the document path becomes the fingerprint only when both outputs agree', () => {
  const sarif = () => [{ruleId: 'owasp:api2:2023-read-restricted', locations: [{physicalLocation: {region: {startLine: 7}}}]}];
  const native = [{code: 'owasp:api2:2023-read-restricted', path: ['paths', '/orders', 'get'], range: {start: {line: 6}}}];
  const results = sarif();
  assert.equal(fingerprint(results, native), true);
  assert.deepEqual(results[0].partialFingerprints, {'documentPath/v1': 'paths./orders.get'});
  assert.equal(fingerprint(sarif(), [{...native[0], code: 'other'}]), false);
  assert.equal(fingerprint(sarif(), [{...native[0], range: {start: {line: 9}}}]), false);
  assert.equal(fingerprint(sarif(), []), false);
});

test('a different or missing Spectral or ruleset is refused', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'testforge-spectral-version-'));
  const pkg = async (name, version) => {
    await mkdir(join(dir, name), {recursive: true});
    await writeFile(join(dir, name, 'package.json'), JSON.stringify({version}));
  };
  try {
    await assert.rejects(installedVersions(dir), /missing/);
    await pkg('@stoplight/spectral-cli', '6.16.3');
    await pkg('@stoplight/spectral-owasp-ruleset', RULESET_VERSION);
    await assert.rejects(installedVersions(dir), /6\.16\.3.*pinned to 6\.17\.0/);
    await pkg('@stoplight/spectral-cli', SPECTRAL_VERSION);
    assert.deepEqual(await installedVersions(dir), {spectral: SPECTRAL_VERSION, ruleset: RULESET_VERSION});
  } finally { await rm(dir, {recursive: true, force: true}); }
});

test('the lint inherits no CI tokens or Node injection', () => {
  assert.deepEqual(childEnvironment({PATH: '/bin', HOME: '/tmp', NODE_OPTIONS: '--require evil', GITHUB_TOKEN: 'secret'}),
    {PATH: '/bin', HOME: '/tmp'});
});
