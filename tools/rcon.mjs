#!/usr/bin/env node
/**
 * Minimal RCON client for the local test server.
 *
 * Minecraft's RCON protocol is tiny: a length-prefixed packet with an id, a type, a null-terminated body and
 * two empty bytes. Types 3 = command, 0 = response. This exists because the panel helper talks to the hosted
 * server; a local one needs its own console.
 *
 * Usage: node tools/rcon.mjs "<command>"
 */
import net from 'node:net';

const HOST = process.env.RCON_HOST || '127.0.0.1';
const PORT = Number(process.env.RCON_PORT || 25575);
const PASSWORD = process.env.RCON_PASSWORD || 'aibuildtest';

function packet(id, type, body) {
  const payload = Buffer.from(body, 'utf8');
  const buf = Buffer.alloc(14 + payload.length);
  buf.writeInt32LE(10 + payload.length, 0);
  buf.writeInt32LE(id, 4);
  buf.writeInt32LE(type, 8);
  payload.copy(buf, 12);
  buf.writeInt16LE(0, 12 + payload.length);
  return buf;
}

function send(socket, id, type, body) {
  return new Promise((resolve, reject) => {
    const buf = packet(id, type, body);
    socket.write(buf);
    const onData = (chunk) => {
      socket.off('data', onData);
      const len = chunk.readInt32LE(0);
      const rid = chunk.readInt32LE(4);
      const rtype = chunk.readInt32LE(8);
      const text = chunk.subarray(12, 4 + len - 2).toString('utf8');
      resolve({ id: rid, type: rtype, body: text });
    };
    socket.on('data', onData);
    socket.once('error', reject);
  });
}

const command = process.argv.slice(2).join(' ');
if (!command) {
  console.error('usage: node tools/rcon.mjs "<command>"');
  process.exit(2);
}

const socket = net.createConnection({ host: HOST, port: PORT }, async () => {
  try {
    const auth = await send(socket, 1, 3, PASSWORD);
    if (auth.id === -1) {
      console.error('auth failed');
      process.exit(1);
    }
    const res = await send(socket, 2, 2, command);
    // strip section-sign colour codes so the output reads as plain text in a terminal
    console.log(res.body.replace(/\u00a7[0-9a-fk-or]/gi, ''));
  } catch (e) {
    console.error('rcon error:', e.message);
    process.exit(1);
  } finally {
    socket.end();
  }
});
