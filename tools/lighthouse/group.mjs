// Leader of one stage's process group. It runs the native LHCI command inside the group, and once
// that command exits it ends the rest of the group (Chrome left behind) while still alive itself, so
// the group id cannot have been reused by an unrelated process when the signal is sent.
import {spawn} from 'node:child_process';

const [command, ...args] = process.argv.slice(2);
// the group-wide SIGTERM below must not end this leader before it reports the command's exit code
process.on('SIGTERM', () => {});
const child = spawn(command, args, {stdio: 'ignore'});
child.once('error', () => process.exit(127));
child.once('close', (code, signal) => {
  try { process.kill(-process.pid, 'SIGTERM'); } catch { /* nothing else left in the group */ }
  process.exit(code ?? (signal ? 128 : 1));
});
