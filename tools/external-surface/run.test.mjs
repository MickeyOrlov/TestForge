import assert from 'node:assert/strict';
import {test} from 'node:test';
import {mkdtemp, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {candidatesFrom, routesFrom, run, servicesFrom, validate} from './run.mjs';

test('scope uses exact canonical origins under the chosen DNS label boundary', () => {
    const domain = 'example.test';
    assert.deepEqual(validate({domain, allowedOrigins: ['https://api.example.test/', 'https://api.example.test']}),
        {domain, discover: true, allowedOrigins: ['https://api.example.test']});
    for (const origin of ['https://notexample.test', 'https://example.test.evil.test',
        'https://user:token@example.test', 'https://example.test/?token=1',
        'https://example.test/#', 'https://example.test/private', 'ftp://example.test']) {
        assert.throws(() => validate({domain, allowedOrigins: [origin]}));
    }
    assert.throws(() => validate({domain: 'localhost'}));
});

test('passive results do not escape the domain or become responding services', () => {
    const candidates = candidatesFrom([
        {host: 'API.EXAMPLE.TEST.'}, {host: 'api.example.test'},
        {host: 'another-example.test'}, {host: 'example.test.evil.test'},
    ], 'example.test');
    assert.deepEqual(candidates.map(row => row.hostname), ['api.example.test']);
    assert.deepEqual(servicesFrom([], ['https://example.test']), [
        {origin: 'https://example.test', source: 'httpx', state: 'not-measured'},
    ]);
    assert.throws(() => servicesFrom([
        {input: 'https://example.test', url: 'http://example.test', failed: false, status_code: 200},
    ], ['https://example.test']));
});

test('crawler inventory excludes synthetic responses and out-of-scope records', () => {
    const gateway = 'http://127.0.0.1:8080';
    const rows = [
        {request: {method: 'GET', endpoint: gateway}, response: {status_code: 200}},
        {request: {method: 'POST', endpoint: gateway + '/write'}, response: {status_code: 405}},
        {request: {method: 'GET', endpoint: gateway + '/synthetic'}, response: {status_code: 502}},
    ];
    assert.deepEqual(routesFrom(rows, gateway, new Map([['/', 200]])), {
        routes: [{path: '/', status: 200, source: 'katana'}], incomplete: false,
    });
    assert.throws(() => routesFrom([
        {request: {method: 'GET', endpoint: 'http://127.0.0.1:8081/'}},
    ], gateway, new Map()));
});

test('a domain-only run reports passive candidates without starting either active tool', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'testforge-passive-'));
    try {
        const scope = join(dir, 'scope.json');
        await writeFile(scope, '{"domain":"example.test"}');
        await writeFile(join(dir, 'subfinder'), `#!/bin/sh
if [ "$1" = -version ]; then
    printf '%s\n' 'Current Version: v2.17.0'
else
    printf '%s\n' '{"host":"api.example.test","sources":["crtsh"]}'
fi
`, {mode: 0o700});
        const {report} = await run(scope, dir, dir);
        assert.equal(report.complete, true, JSON.stringify(report));
        assert.deepEqual(report.candidates, [
            {hostname: 'api.example.test', source: 'subfinder', providers: ['crtsh']},
        ]);
        assert.equal(report.stages.httpx.state, 'skipped');
        assert.equal(report.stages.katana.state, 'skipped');
        assert.deepEqual(report.services, []);
        await rm(join(dir, 'subfinder'));
        const failed = await run(scope, dir, dir);
        assert.equal(failed.report.complete, false);
        assert.equal(failed.report.stages.subfinder.state, 'incomplete');
        assert.equal(failed.report.stages.httpx.state, 'skipped');
    } finally {
        await rm(dir, {recursive: true, force: true});
    }
});
