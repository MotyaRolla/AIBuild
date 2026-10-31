#!/usr/bin/env node
/**
 * Convenience wrapper around `server.mjs`, kept for symmetry with the other packages
 * (`packages/core/src/cli.mjs`). `server.mjs` is itself runnable, so either entry point works:
 *
 *   node packages/studio/src/server.mjs [--port 8137] [--host 127.0.0.1] [--root PATH]
 *   node packages/studio/src/cli.mjs    [--port 8137] [--host 127.0.0.1] [--root PATH]
 */
import { start, DEFAULT_PORT } from './server.mjs';

const argv = process.argv.slice(2);
const args = { port: DEFAULT_PORT, host: '127.0.0.1', root: undefined };
for (let i = 0; i < argv.length; i++) {
  const arg = argv[i];
  if (arg === '--port' || arg === '-p') args.port = Number(argv[++i]);
  else if (arg === '--host') args.host = argv[++i];
  else if (arg === '--root') args.root = argv[++i];
  else if (arg === '--help' || arg === '-h') {
    console.log('usage: node packages/studio/src/cli.mjs [--port N] [--host H] [--root DIR]');
    process.exit(0);
  }
}

const app = await start({ port: args.port, host: args.host, root: args.root });
console.log(`AIBuild studio: ${app.url}`);
console.log(`  builds: ${app.roots.outDir}`);
console.log(`  public: ${app.roots.publicDir}`);
console.log(`  three:  ${app.roots.threeDir}`);

let closing = false;
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, async () => {
    if (closing) process.exit(1);
    closing = true;
    await app.close();
    process.exit(0);
  });
}
