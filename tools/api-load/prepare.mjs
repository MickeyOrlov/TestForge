// Project composition only. API modules communicate through files; k6 client generation belongs to Grafana.
import { readFile, writeFile, mkdir, readdir, copyFile } from 'node:fs/promises';
import { resolve, join, relative, dirname } from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { parse as parseYaml } from 'yaml';

const toolDir = dirname(fileURLToPath(import.meta.url));
const generatorVersion = '0.4.1';
const helperHash = '43812073b0b2fdb09edd506098ea9247d6d16c373894d8e843864e5df813dddd';
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const json = async file => {
  try { return JSON.parse(await readFile(file, 'utf8')); }
  catch { throw new Error('Could not read a required JSON artifact'); }
};
const writeJson = (file, data) => writeFile(file, JSON.stringify(data, null, 2) + '\n');
const requireThat = (condition, message) => { if (!condition) throw new Error(message); };

export function runGenerator(cli, input, generated) {
  // Generator output is intentionally private and unused. Discard it instead of buffering it.
  return spawnSync(process.execPath, [cli, '--disable-analytics', '--mode', 'single', input, generated],
    { stdio: 'ignore', timeout: 120_000, env: { ...process.env, DISABLE_ANALYTICS: 'true' } });
}

export function validateSpec(spec) {
  requireThat(spec && typeof spec.openapi === 'string' && spec.openapi.startsWith('3.'), 'Use a bundled OpenAPI 3 document');
  const visit = value => {
    if (!value || typeof value !== 'object') return;
    if ('$ref' in value) requireThat(typeof value.$ref === 'string' && value.$ref.startsWith('#'),
      'Bundle external $ref files with an existing OpenAPI bundler before preparing load inputs');
    Object.values(value).forEach(visit);
  };
  visit(spec);
}

export function validateSelection(selection, allowWrites = false) {
  requireThat(Array.isArray(selection) && selection.length > 0 && selection.every(key =>
    typeof key === 'string' && /^(GET|HEAD|OPTIONS|POST|PUT|PATCH|DELETE) \/[^\s?#*,]*$/.test(key)),
  'Select explicit METHOD /path operation keys in a non-empty JSON array');
  requireThat(new Set(selection).size === selection.length, 'Selected operations must not repeat');
  requireThat(allowWrites || selection.every(key => /^(GET|HEAD|OPTIONS) /.test(key)),
    'Write operations require explicit apiLoadAllowWrites=true and a reviewed project scenario');
  return [...selection].sort();
}

async function files(root, prefix = '') {
  const result = [];
  for (const entry of await readdir(join(root, prefix), { withFileTypes: true })) {
    const name = join(prefix, entry.name);
    requireThat(!entry.isSymbolicLink(), 'Scenario assets must not contain symlinks');
    requireThat(!['.git', 'node_modules', '.env'].includes(entry.name), 'Scenario directory contains a reserved asset');
    if (entry.isDirectory()) result.push(...await files(root, name));
    else if (entry.isFile()) result.push(name.replaceAll('\\', '/'));
  }
  return result.sort();
}

async function hashFiles(root) {
  const result = {};
  for (const name of await files(root)) result[name] = sha256(await readFile(join(root, name)));
  return result;
}

async function copyTree(source, destination) {
  for (const name of await files(source)) {
    await mkdir(dirname(join(destination, name)), { recursive: true });
    await copyFile(join(source, name), join(destination, name));
  }
}

export async function snapshot({ spec, scenario, selection, run, allowWrites = false }) {
  const inside = relative(resolve(scenario), resolve(run));
  requireThat(inside.startsWith('..'), 'Output must be outside the scenario source directory');
  let document;
  try { document = parseYaml(await readFile(spec, 'utf8')); }
  catch { throw new Error('Could not parse the local OpenAPI document'); }
  validateSpec(document);
  const selected = validateSelection(await json(selection), allowWrites);
  requireThat((await files(scenario)).includes('scenario.ts'), 'Scenario directory must contain native scenario.ts');
  for (const name of await readdir(scenario)) requireThat(!['client.ts', 'vendor', 'provenance.json', 'input'].includes(name),
    'Scenario assets collide with a reserved prepared-bundle name');
  // A unique run directory is mandatory: no earlier successful report can leak into a new pipeline.
  await mkdir(run, { recursive: false });
  const input = join(run, 'input');
  await mkdir(join(input, 'scenario'), { recursive: true });
  await writeJson(join(input, 'openapi.json'), document);
  await writeJson(join(input, 'operations.json'), selected);
  await copyTree(scenario, join(input, 'scenario'));
  const hashes = await hashFiles(input);
  await writeJson(join(run, 'inputs.json'), { formatVersion: 1, specId: 'api', allowWrites, files: hashes });
}

export async function verifyInputs(run) {
  const inputs = await json(join(run, 'inputs.json'));
  requireThat(inputs.formatVersion === 1, 'Unsupported input snapshot version');
  const actual = await hashFiles(join(run, 'input'));
  requireThat(JSON.stringify(actual) === JSON.stringify(inputs.files), 'Input snapshot changed after research started');
  return inputs;
}

export function validateResearch(discovery, catalog, exploration, selected, targetHash) {
  requireThat(discovery.enabled === true && discovery.healthy === true, 'Discovery did not complete successfully');
  requireThat(exploration.enabled === true, 'API exploration was not enabled');
  requireThat(catalog.specId === 'api', 'Catalog does not belong to the selected API snapshot');
  const specs = exploration.specs?.filter(spec => spec.specId === 'api');
  requireThat(specs?.length === 1 && !specs[0].error, 'Missing or ambiguous API exploration result');
  requireThat(sha256(specs[0].baseUrl ?? '') === targetHash, 'Research and load target differ; rerun research');
  const declared = new Set(catalog.endpoints?.map(endpoint => endpoint.key));
  for (const key of selected) {
    requireThat(declared.has(key), `Selected operation is absent from the catalog: ${key}`);
    const observations = specs[0].observations?.filter(observation => observation.key === key);
    requireThat(observations?.length === 1 && observations[0].outcome === 'PASSED',
      `Selected operation was not successfully explored: ${key}`);
  }
}

export async function prepare({ run }) {
  const inputs = await verifyInputs(run);
  const attestation = await json(join(run, 'research.json'));
  requireThat(attestation.formatVersion === 1 && attestation.specSha256 === inputs.files['openapi.json'],
    'Research does not attest this OpenAPI snapshot');
  const input = join(run, 'input');
  const reports = {
    discovery: 'discovery/report.json', catalog: 'discovery/api/catalog.json', exploration: 'exploration/report.json',
  };
  for (const [key, path] of Object.entries(reports)) {
    requireThat(sha256(await readFile(join(run, path))) === attestation.files[path], `Research artifact changed: ${key}`);
  }
  const selected = validateSelection(await json(join(input, 'operations.json')), inputs.allowWrites);
  const target = process.env.TESTFORGE_API_LOAD_TARGET;
  requireThat(target && sha256(target) === attestation.targetSha256, 'Research and load target differ; rerun research');
  validateResearch(await json(join(run, reports.discovery)), await json(join(run, reports.catalog)),
    await json(join(run, reports.exploration)), selected, attestation.targetSha256);
  const installed = await json(join(toolDir, 'node_modules/@grafana/openapi-to-k6/package.json'));
  requireThat(installed.version === generatorVersion, 'Run npm ci to restore the pinned client generator');
  const generated = join(run, 'generated');
  const result = runGenerator(join(toolDir, 'node_modules/@grafana/openapi-to-k6/dist/cli.js'),
    join(input, 'openapi.json'), generated);
  requireThat(result.status === 0 && !result.error, 'The pinned openapi-to-k6 generator failed; inspect the input document');
  const clients = (await readdir(generated)).filter(name => name.endsWith('.ts'));
  requireThat(clients.length === 1, 'Expected one upstream TypeScript client in single mode');
  let client = await readFile(join(generated, clients[0]), 'utf8');
  const remoteHelper = 'https://jslib.k6.io/url/1.0.0/index.js';
  requireThat(client.includes(remoteHelper), 'Upstream runtime imports changed; review the generator integration');
  client = '// Project preparation: the upstream URL helper import below was made local.\n'
    + client.replaceAll(remoteHelper, './vendor/url.js');
  const helper = await readFile(join(toolDir, 'vendor/url.js'));
  requireThat(sha256(helper) === helperHash, 'Vendored upstream URL helper checksum mismatch');
  const prepared = join(run, 'prepared');
  await mkdir(prepared, { recursive: false });
  await copyTree(join(input, 'scenario'), prepared);
  await mkdir(join(prepared, 'vendor'));
  await writeFile(join(prepared, 'client.ts'), client);
  await writeFile(join(prepared, 'vendor/url.js'), helper);
  await copyFile(join(toolDir, 'vendor/LICENSE.url'), join(prepared, 'vendor/LICENSE.url'));
  const hashes = await hashFiles(prepared);
  await writeJson(join(prepared, 'provenance.json'), {
    formatVersion: 1, generator: '@grafana/openapi-to-k6', generatorVersion,
    specSha256: attestation.specSha256, targetSha256: attestation.targetSha256,
    selectedOperations: selected, inputFiles: inputs.files, researchFiles: attestation.files,
    files: hashes, entrypoint: 'scenario.ts',
  });
  return prepared;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [phase, ...args] = process.argv.slice(2);
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    requireThat(args[i]?.startsWith('--') && args[i + 1], 'Expected --name value arguments');
    options[args[i].slice(2)] = args[i + 1];
  }
  try {
    if (phase === 'snapshot') {
      await snapshot({ ...options, run: resolve(options.run), allowWrites: options['allow-writes'] === 'true' });
    } else if (phase === 'prepare') await prepare({ run: resolve(options.run) });
    else throw new Error('Expected snapshot or prepare phase');
  } catch (error) {
    // Do not print source document contents, generator output or environment credentials.
    console.error(`API load preparation failed: ${error.message}`);
    process.exitCode = 1;
  }
}
