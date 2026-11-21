import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {isIP} from 'node:net';
import {isAbsolute, join, resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {crawlThrough} from './gateway.mjs';

export const versions = {subfinder: '2.17.0', httpx: '1.12.0', katana: '1.8.0'};
const dnsLabel = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
const maxOutput = 4 * 1024 * 1024;
const publicName = name => typeof name === 'string' && name.length <= 253
    && name.includes('.') && name.split('.').every(label => dnsLabel.test(label)) && !isIP(name);
const belongsTo = (host, domain) => host === domain || host.endsWith('.' + domain);

export function validate(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)
        || Object.keys(value).some(key => !['domain', 'discover', 'allowedOrigins'].includes(key))) {
        throw Error('Expected a scope object with domain, discover and allowedOrigins');
    }
    const {domain, discover = true, allowedOrigins = []} = value;
    const localFixture = domain === 'localhost' && discover === false;
    if ((!publicName(domain) && !localFixture) || typeof discover !== 'boolean') {
        throw Error('Select a canonical DNS domain; localhost requires discover=false');
    }
    if (!Array.isArray(allowedOrigins) || allowedOrigins.length > 20) {
        throw Error('Select at most 20 explicit HTTP(S) origins');
    }
    for (const origin of allowedOrigins) {
        const url = new URL(origin);
        if (!['http:', 'https:'].includes(url.protocol) || url.port === '0'
            || (origin !== url.origin && origin !== url.origin + '/')
            || (localFixture ? url.hostname !== 'localhost' : !belongsTo(url.hostname, domain))) {
            throw Error('Every allowed origin must be canonical and within the selected domain');
        }
    }
    if (!discover && allowedOrigins.length === 0) {
        throw Error('Enable passive discovery or select at least one explicit origin');
    }
    return {domain, discover, allowedOrigins: [...new Set(allowedOrigins.map(value => new URL(value).origin))].sort()};
}

class NativeFailure extends Error {}

function command(executable, args, directory, timeout, signal) {
    if (signal.aborted) return Promise.reject(new NativeFailure('interrupted'));
    return new Promise((resolveCommand, reject) => {
        const child = spawn(executable, args, {
            cwd: directory,
            env: {HOME: directory, XDG_CONFIG_HOME: directory, TMPDIR: directory},
            stdio: ['ignore', 'pipe', 'pipe'],
            detached: true,
        });
        const stdout = [], stderr = [];
        let bytes = 0;
        let failure;
        const kill = () => {
            if (child.pid) {
                try { process.kill(-child.pid, 'SIGKILL'); } catch { /* already exited */ }
            }
        };
        const stop = reason => {
            failure ??= reason;
            kill();
        };
        const timer = setTimeout(() => stop('timeout'), timeout);
        const interrupt = () => stop('interrupted');
        signal.addEventListener('abort', interrupt, {once: true});
        const capture = chunks => chunk => {
            bytes += chunk.length;
            if (bytes > maxOutput) stop('output limit');
            else chunks.push(chunk);
        };
        child.stdout.on('data', capture(stdout));
        child.stderr.on('data', capture(stderr));
        child.once('error', () => stop('could not start'));
        child.once('close', code => {
            clearTimeout(timer);
            signal.removeEventListener('abort', interrupt);
            kill();
            if (failure || code !== 0) reject(new NativeFailure(failure ?? 'nonzero exit'));
            else resolveCommand({stdout: Buffer.concat(stdout).toString(), stderr: Buffer.concat(stderr).toString()});
        });
    });
}

function jsonLines(stdout) {
    return stdout.split('\n').filter(line => line.trim()).map(line => JSON.parse(line));
}

export function candidatesFrom(rows, domain) {
    const hosts = new Map();
    for (const row of rows) {
        if (!row || typeof row.host !== 'string') throw Error('Invalid Subfinder record');
        const host = row.host.toLowerCase().replace(/\.$/, '');
        if (!publicName(host) || !belongsTo(host, domain)) continue;
        if (!hosts.has(host)) hosts.set(host, new Set());
        const providers = Array.isArray(row.sources) ? row.sources : [];
        for (const provider of providers) {
            if (typeof provider === 'string' && /^[a-z0-9_-]{1,40}$/i.test(provider)) {
                hosts.get(host).add(provider);
            }
        }
    }
    return [...hosts.keys()].sort().map(hostname => ({
        hostname, source: 'subfinder', providers: [...hosts.get(hostname)].sort(),
    }));
}

export function servicesFrom(rows, origins) {
    const records = new Map();
    for (const row of rows) {
        const origin = new URL(row.input).origin;
        const target = new URL(row.url);
        if (!origins.includes(origin) || target.origin !== origin || target.pathname !== '/'
            || target.search || target.hash || target.username || target.password || records.has(origin)) {
            throw Error('Unexpected httpx probe record');
        }
        if (row.failed === true) records.set(origin, {state: 'no-response'});
        else if (row.failed === false && Number.isInteger(row.status_code)
            && row.status_code >= 100 && row.status_code <= 599) {
            records.set(origin, {state: 'responded', status: row.status_code});
        } else throw Error('Invalid httpx probe record');
    }
    return origins.map(origin => ({origin, source: 'httpx', ...(records.get(origin) ?? {state: 'not-measured'})}));
}

export function routesFrom(rows, gateway, observations) {
    let rootRecorded = false;
    let incomplete = false;
    for (const row of rows) {
        const url = new URL(row.request.endpoint);
        if (url.origin !== gateway || url.username || url.password) throw Error('Unexpected Katana origin');
        if (row.request.method !== 'GET' || url.search || url.hash) continue;
        if (row.error) incomplete = true;
        const status = row.response?.status_code;
        if (url.pathname === '/' && Number.isInteger(status) && status === observations.get('/')) {
            rootRecorded = true;
        }
    }
    // Katana may suppress records for fetched pages (for example similar responses).
    // The gateway only records requests that reached the selected upstream and returned.
    const routes = [...observations.entries()].sort(([a], [b]) => a.localeCompare(b))
        .map(([path, status]) => ({path, status, source: 'katana'}));
    return {routes, incomplete: incomplete || !rootRecorded};
}

const stageState = (state, reason) => ({state, ...(reason ? {reason} : {})});
const escapeRegex = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const common = ['-silent', '-duc', '-no-color'];

async function inventory(config, tools, directory, signal) {
    const {domain, discover, allowedOrigins} = config;
    const report = {formatVersion: 1, scope: config, tools: versions, stages: {},
        candidates: [], services: [], routes: []};
    const invoke = async (tool, args, timeout) => {
        const executable = join(tools, tool);
        const version = await command(executable, ['-version'], directory, 5000, signal);
        if (!new RegExp('\\bv' + escapeRegex(versions[tool]) + '(?:\\s|$)').test(version.stdout + version.stderr)) {
            throw new NativeFailure('tool version mismatch');
        }
        return command(executable, args, directory, timeout, signal);
    };
    const stage = async (name, work) => {
        try {
            report.stages[name] = await work();
        } catch (failure) {
            report.stages[name] = stageState('incomplete',
                failure instanceof NativeFailure ? failure.message : 'native output or setup failed validation');
        }
    };

    report.stages.subfinder = stageState('skipped', 'passive discovery disabled');
    if (discover) {
        await stage('subfinder', async () => {
            const configFile = join(directory, 'subfinder.yml');
            await writeFile(configFile, '{}\n');
            const result = await invoke('subfinder',
                [...common, '-d', domain, '-config', configFile, '-pc', configFile,
                    '-oJ', '-cs', '-rl', '5', '-timeout', '8', '-max-time', '1'], 70000);
            report.candidates = candidatesFrom(jsonLines(result.stdout), domain);
            return stageState('complete');
        });
    }

    report.stages.httpx = stageState('skipped', 'no approved origins');
    report.services = servicesFrom([], allowedOrigins);
    if (allowedOrigins.length) {
        await stage('httpx', async () => {
            const targets = join(directory, 'origins.txt');
            await writeFile(targets, allowedOrigins.join('\n') + '\n');
            const result = await invoke('httpx',
                [...common, '-l', targets, '-nfs', '-probe', '-j', '-sc', '-x', 'GET', '-ob',
                    '-t', '1', '-rl', '5', '-timeout', '4', '-retries', '0',
                    '-rstr', '262144', '-no-stdin', '-auth=false', '-cdn=false'], 90000);
            report.services = servicesFrom(jsonLines(result.stdout), allowedOrigins);
            return report.services.some(row => row.state === 'not-measured')
                ? stageState('incomplete', 'missing probe results') : stageState('complete');
        });
    }

    report.stages.katana = stageState('skipped', 'no responding origins');
    const responding = report.services.filter(row => row.state === 'responded');
    const crawlTargets = responding.filter(row => row.status < 300 || row.status >= 400);
    for (const service of responding) {
        if (!crawlTargets.includes(service)) service.crawl = 'skipped-redirect';
    }
    if (responding.length && !crawlTargets.length) {
        report.stages.katana = stageState('skipped', 'all responding origins redirect');
    }
    if (crawlTargets.length) {
        await stage('katana', async () => {
            for (const service of crawlTargets) {
                const {origin} = service;
                try {
                    const result = await crawlThrough(origin, async gateway => {
                        const output = await invoke('katana',
                            [...common, '-u', gateway, '-j', '-dr', '-fs', 'fqdn',
                                '-cs', '^' + escapeRegex(gateway) + '(/|$)', '-cos', '[?]',
                                '-or', '-ob', '-d', '2', '-ct', '10s', '-c', '1', '-p', '1',
                                '-rl', '5', '-retry', '0', '-timeout', '4', '-mrs', '262144',
                                '-mdp', '30'], 18000);
                        return jsonLines(output.stdout);
                    });
                    const crawl = routesFrom(result.rows, result.gateway, result.observations);
                    report.routes.push(...crawl.routes.map(route => ({origin, ...route})));
                    service.excludedRequests = result.excluded;
                    service.crawl = crawl.incomplete || result.incomplete ? 'incomplete' : 'complete';
                } catch (failure) {
                    service.crawl = 'incomplete';
                    service.crawlReason = failure instanceof NativeFailure
                        ? failure.message : 'native output or gateway failed validation';
                }
            }
            return crawlTargets.some(service => service.crawl === 'incomplete')
                ? stageState('incomplete', 'missing or failed crawl results') : stageState('complete');
        });
    }
    report.complete = Object.values(report.stages).every(stage => stage.state !== 'incomplete');
    return report;
}

export async function run(configPath, tools, output) {
    if (process.platform === 'win32' || !isAbsolute(tools)) {
        throw Error('Use POSIX and an absolute path to the pinned tools directory');
    }
    const config = validate(JSON.parse(await readFile(configPath, 'utf8')));
    const directory = resolve(output, randomUUID());
    const work = join(directory, 'private');
    await mkdir(work, {recursive: true, mode: 0o700});
    const controller = new AbortController();
    const interrupt = () => controller.abort();
    process.once('SIGINT', interrupt);
    process.once('SIGTERM', interrupt);
    let report;
    try {
        report = await inventory(config, tools, work, controller.signal);
    } finally {
        process.removeListener('SIGINT', interrupt);
        process.removeListener('SIGTERM', interrupt);
        await rm(work, {recursive: true, force: true});
    }
    await writeFile(join(directory, 'surface.json'), JSON.stringify(report, null, 2) + '\n', {mode: 0o600});
    return {report, directory};
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try {
        if (process.argv.length !== 5) throw Error('Expected scope, tools directory and output directory');
        const {report, directory} = await run(...process.argv.slice(2));
        console.log((report.complete ? 'COMPLETED ' : 'INCOMPLETE ') + directory);
        process.exitCode = report.complete ? 0 : 2;
    } catch {
        console.error('External inventory failed. Check the explicit scope, tool paths and output directory.');
        process.exitCode = 2;
    }
}
