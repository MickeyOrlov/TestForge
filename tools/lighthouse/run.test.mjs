import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validate, childEnvironment} from './run.mjs';
const config = () => ({ci: {collect: {numberOfRuns: 1}, assert: {assertions: {'first-contentful-paint': ['error', {maxNumericValue: 10000}]}}}});
test('targets and native assertions must be explicit; no server commands or public upload', () => {
  validate(config(), ['http://127.0.0.1:8000/'], '/chrome');
  for (const url of ['https://user:password@example.org/', 'https://example.org/?token=a', 'file:///etc/passwd'])
    assert.throws(() => validate(config(), [url], '/chrome'));
  for (const change of [c => c.ci.upload = {target:'temporary-public-storage'},
    c => c.ci.collect.startServerCommand = 'echo secret', c => c.ci.assert.assertions = {},
    c => c.ci.assert.assertions = {'first-contentful-paint':'warn'}]) {
    const c = config(); change(c); assert.throws(() => validate(c, ['https://example.org/'], '/chrome'));
  }
});
test('native children do not inherit CI tokens, LHCI options or Node injection', () => {
  assert.deepEqual(childEnvironment({PATH:'/bin', HOME:'/tmp', NODE_OPTIONS:'--require evil',
    LHCI_TOKEN:'secret', GITHUB_TOKEN:'secret', AWS_SECRET_ACCESS_KEY:'secret'}), {PATH:'/bin',HOME:'/tmp'});
});
