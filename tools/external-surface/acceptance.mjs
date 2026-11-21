import assert from 'node:assert/strict';
import {mkdtemp, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {createServer} from 'node:http';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {run} from './run.mjs';

const bind = async handler => {
    const server = createServer(handler);
    await new Promise(ready => server.listen(0, '127.0.0.1', ready));
    return {server, origin: 'http://localhost:' + server.address().port};
};

const tools = process.argv[2];
if (!tools) throw Error('Select an absolute pinned tools directory');
const output = await mkdtemp(join(tmpdir(), 'testforge-surface-'));
const requests = [];
let outsideRequests = 0;
const outside = await bind((_, response) => {
    outsideRequests++;
    response.end('Outside scope');
});
const redirect = await bind((_, response) => {
    response.writeHead(302, {location: outside.origin + '/escape'}).end();
});
const fixture = await bind((request, response) => {
    requests.push({method: request.method, url: request.url, cookie: request.headers.cookie});
    if (request.url === '/redirect') {
        response.writeHead(302, {location: outside.origin + '/escape'}).end();
        return;
    }
    response.setHeader('Content-Type', 'text/html');
    response.setHeader('Set-Cookie', 'fixture-cookie=synthetic-secret');
    if (request.url === '/') {
        response.end(`<html><body>
          <a href="/public">Public path</a>
          <a href="/redirect">Redirect</a>
          <a href="/with-query?token=synthetic-secret">Query</a>
          <a href="${outside.origin}/same-host-other-port">Different port</a>
          <button hx-post="/must-not-write">Write</button>
          </body></html>`);
    } else response.end('<html><body>Page</body></html>');
});

try {
    const scope = join(output, 'scope.json');
    await writeFile(scope, JSON.stringify({domain: 'localhost', discover: false,
        allowedOrigins: [fixture.origin, redirect.origin]}));
    const result = await run(scope, tools, output);
    assert.equal(result.report.complete, true, JSON.stringify(result.report));
    assert.equal(result.report.stages.subfinder.state, 'skipped');
    const service = result.report.services.find(row => row.origin === fixture.origin);
    assert.equal(service.status, 200);
    assert.equal(result.report.services.find(row => row.origin === redirect.origin)?.crawl, 'skipped-redirect');
    assert.equal(result.report.routes.find(route => route.path === '/public')?.status, 200);
    assert.equal(result.report.routes.find(route => route.path === '/redirect')?.status, 302);
    assert.ok(service.excludedRequests.method > 0, 'HTMX write reached the gateway');
    assert.ok(requests.every(row => row.method === 'GET' && !row.cookie
        && !row.url.includes('?') && row.url !== '/must-not-write'));
    assert.equal(outsideRequests, 0);
    assert.deepEqual(await readdir(result.directory), ['surface.json']);
    const evidence = await readFile(join(result.directory, 'surface.json'), 'utf8');
    assert.ok(!evidence.includes('synthetic-secret') && !evidence.includes(outside.origin));
    console.log('Native httpx and Katana acceptance passed');
} finally {
    for (const {server} of [fixture, outside, redirect]) {
        server.closeAllConnections();
        await new Promise(ready => server.close(ready));
    }
    await rm(output, {recursive: true, force: true});
}
