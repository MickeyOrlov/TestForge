import {createServer} from 'node:http';
import {mkdtemp, writeFile, readFile, readdir, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import assert from 'node:assert/strict';
import {run} from './run.mjs';
const chrome = process.argv[2];
if (!chrome) throw Error('Explicit absolute Chrome executable required');
const server = createServer((req,res) => {res.setHeader('Content-Type','text/html');res.end('<!doctype html><html lang="en"><head><title>Owned performance fixture</title><meta name="viewport" content="width=device-width"></head><body><main><h1>TestForge Lighthouse fixture</h1></main></body></html>');});
await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
const inputs = await mkdtemp(join(tmpdir(),'testforge-lighthouse-'));
try {
  const urlsPath = join(inputs,'urls.json'), configPath = join(inputs,'config.json');
  await writeFile(urlsPath, JSON.stringify([`http://127.0.0.1:${server.address().port}/`]));
  const results = [];
  for (const [limit, expected] of [[60000,0],[0,1]]) {
    await writeFile(configPath, JSON.stringify({ci:{collect:{numberOfRuns:1,settings:{onlyCategories:['performance'],disableFullPageScreenshot:true}},
      assert:{assertions:{'first-contentful-paint':['error',{maxNumericValue:limit}]}}}}));
    const result = await run({configPath,urlsPath,chrome}); results.push(result);
    assert.equal(result.exitCode, expected, JSON.stringify(result));
    const files = await readdir(join(result.dir,'reports'));
    assert.ok(files.some(f => f.endsWith('.html')) && files.some(f => f.endsWith('.json')));
    const native = JSON.parse(await readFile(join(result.dir,'.lighthouseci/assertion-results.json'),'utf8'));
    assert.ok(native.length > 0); assert.equal(native[0].passed, expected === 0);
  }
  assert.notEqual(results[0].dir,results[1].dir);
  console.log(JSON.stringify({passed:results[0].dir,blocked:results[1].dir}));
} finally {server.close(); await rm(inputs,{recursive:true,force:true});}
