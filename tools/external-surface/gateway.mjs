import {createServer} from 'node:http';

const maxResponseBytes = 262144;
const maxRequests = 40;

// The crawler sees only a loopback origin. Its discoveries and request headers never
// determine the upstream origin, and the gateway never follows upstream redirects.
export async function crawlThrough(origin, crawl) {
    const observations = new Map();
    const pending = new AbortController();
    const excluded = {method: 0, url: 0, budget: 0};
    let incomplete = false;
    let requests = 0;
    const server = createServer(async (request, response) => {
        const reject = (kind, status) => {
            excluded[kind]++;
            response.writeHead(status).end();
        };
        if (request.method !== 'GET') return reject('method', 405);
        const path = request.url;
        if (!path?.startsWith('/') || path.startsWith('//') || /[?\\#]/.test(path)) {
            return reject('url', 403);
        }
        if (requests >= maxRequests) {
            incomplete = true;
            return reject('budget', 429);
        }
        requests++;
        try {
            const target = new URL(origin + path);
            if (target.origin !== origin) return reject('url', 403);
            const upstream = await fetch(target, {
                redirect: 'manual',
                signal: AbortSignal.any([pending.signal, AbortSignal.timeout(4000)]),
            });
            const chunks = [];
            let bytes = 0;
            if (upstream.body) {
                const reader = upstream.body.getReader();
                try {
                    for (;;) {
                        const {done, value} = await reader.read();
                        if (done) break;
                        bytes += value.length;
                        if (bytes > maxResponseBytes) throw Error('response limit');
                        chunks.push(value);
                    }
                } finally {
                    await reader.cancel().catch(() => {});
                }
            }
            observations.set(target.pathname, upstream.status);
            const contentType = upstream.headers.get('content-type');
            response.writeHead(upstream.status, contentType ? {'content-type': contentType} : {});
            response.end(Buffer.concat(chunks));
        } catch {
            incomplete = true;
            response.writeHead(502).end();
        }
    });
    server.on('clientError', (_, socket) => socket.destroy());
    await new Promise((ready, reject) => {
        server.once('error', reject);
        server.listen(0, '127.0.0.1', ready);
    });
    try {
        const gateway = 'http://127.0.0.1:' + server.address().port;
        const rows = await crawl(gateway);
        return {gateway, rows, observations, excluded, incomplete};
    } finally {
        pending.abort();
        await new Promise(ready => {
            server.close(ready);
            server.closeAllConnections();
        });
    }
}
