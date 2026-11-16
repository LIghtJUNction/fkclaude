import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import { spawnSync } from 'node:child_process';

const scriptPath = fileURLToPath(new URL('../app/src/main/assets/audit.js', import.meta.url));
const script = readFileSync(scriptPath, 'utf8');

function fixture(options = {}) {
  const messages = [], calls = [];
  let now = 0;
  const response = options.response || {
    status: 204,
    text() { throw new Error('Audit must not read response text'); },
    json() { throw new Error('Audit must not read response JSON'); },
    arrayBuffer() { throw new Error('Audit must not read response bytes'); },
    clone() { throw new Error('Audit must not clone the response'); },
  };
  const promise = options.promise || Promise.resolve(response);
  const returnValue = {};
  class Clock extends Date { static now() { return now; } }
  class Request {
    constructor(url, init = {}) { this.url = url; this.method = init.method || 'GET'; }
  }
  class XHR {
    constructor() { this.status = 204; this.listeners = new Map(); }
    open(...args) { calls.push({kind:'open', receiver:this, args}); return returnValue; }
    send(...args) { calls.push({kind:'send', receiver:this, args}); return returnValue; }
    addEventListener(name, callback) {
      if (options.brokenListener) throw new Error('Observer listener unavailable');
      const listeners = this.listeners.get(name) || [];
      listeners.push(callback); this.listeners.set(name, listeners);
    }
    finish() {
      for (const callback of this.listeners.get('loadend') || []) callback();
      this.listeners.delete('loadend');
    }
  }
  class Socket {
    constructor() { this.url = 'wss://claude.ai/socket'; }
    send(...args) { calls.push({kind:'socket', receiver:this, args}); return returnValue; }
  }
  class Canvas {
    toDataURL(...args) { calls.push({kind:'canvas', receiver:this, args}); return 'private-canvas-result'; }
    toBlob(...args) { calls.push({kind:'canvasBlob', receiver:this, args}); return returnValue; }
  }
  class CanvasContext {
    getImageData(...args) { calls.push({kind:'canvasData', receiver:this, args}); return returnValue; }
  }
  let getterReads = 0;
  const prototype = {};
  Object.defineProperty(prototype, 'userAgent', {
    configurable: true, enumerable: true,
    get() { getterReads++; return 'private-device-value'; },
  });
  const navigator = Object.assign(Object.create(prototype), {
    sendBeacon(...args) { calls.push({kind:'beacon', receiver:this, args}); return options.beaconResult ?? true; },
    geolocation: {
      getCurrentPosition(...args) { calls.push({kind:'location', receiver:this, args}); return returnValue; },
      watchPosition(...args) { calls.push({kind:'watchLocation', receiver:this, args}); return 17; },
    },
    clipboard: {
      read(...args) { calls.push({kind:'clipboard', receiver:this, args}); return promise; },
      readText(...args) { calls.push({kind:'clipboardText', receiver:this, args}); return promise; },
    },
    mediaDevices: {
      getUserMedia(...args) { calls.push({kind:'media', receiver:this, args}); return promise; },
      enumerateDevices(...args) { calls.push({kind:'devices', receiver:this, args}); return promise; },
    },
  });
  const originalFetch = options.fetch || function(...args) {
    calls.push({kind:'fetch', receiver:this, args});
    if (options.readMethod) void args[1]?.method;
    return promise;
  };
  const context = vm.createContext({
    Date:Clock, Promise, URL, Request, ArrayBuffer, Uint8Array, Blob,
    navigator, XMLHttpRequest:XHR, WebSocket:Socket,
    HTMLCanvasElement:Canvas, CanvasRenderingContext2D:CanvasContext,
    location:{href:'https://claude.ai/app/current'},
    fetch:originalFetch,
    ...(options.noBridge ? {} : {fkclaudeAuditBridge:{postMessage(value) { messages.push(JSON.parse(value)); }}}),
  });
  const run = () => vm.runInContext(script, context, {timeout:2000});
  run();
  return { context, messages, calls, response, promise, returnValue, run,
    setClock(value) { now = value; }, getterReads:() => getterReads };
}

test('fetch preserves promise, arguments, receiver, and response body ownership', async () => {
  const f = fixture();
  const body = {secret:'SENSITIVE_CHAT_BODY', toString() { throw new Error('Body coercion forbidden'); }};
  const init = {method:'POST', body, headers:{Authorization:'SECRET_AUTHORIZATION'}};
  const receiver = {};
  const returned = f.context.fetch.call(receiver, 'https://claude.ai/api/organizations/secret-id/chat_conversations', init);
  assert.equal(returned, f.promise);
  assert.equal(await returned, f.response);
  await Promise.resolve();
  assert.equal(f.calls[0].receiver, receiver);
  assert.equal(f.calls[0].args[1], init);
  assert.equal(f.calls[0].args[1].body, body);
  assert.ok(f.messages.some(event => event.action === 'response' && event.status === 204));
  const encoded = JSON.stringify(f.messages);
  assert.ok(!encoded.includes('SENSITIVE_CHAT_BODY'));
  assert.ok(!encoded.includes('SECRET_AUTHORIZATION'));
});

test('native thrown exceptions retain identity', () => {
  const originalError = new TypeError('native-failure');
  const f = fixture({fetch() { throw originalError; }});
  assert.throws(() => f.context.fetch('/failing'), error => error === originalError);
  assert.ok(f.messages.some(event => event.action === 'failure'));
});

test('native rejected promises retain identity and rejection', async () => {
  const error = new Error('native rejection');
  const promise = Promise.reject(error);
  const f = fixture({promise});
  assert.equal(f.context.fetch('/reject'), promise);
  await assert.rejects(promise, observed => observed === error);
  await Promise.resolve();
  assert.ok(f.messages.some(event => event.action === 'failure'));
});

test('relative network targets become absolute for the native bridge validator', async () => {
  const f = fixture();
  await f.context.fetch('/api/event_logging/v2/batch');
  const xhr = new f.context.XMLHttpRequest();
  xhr.open('POST', '../collect');
  xhr.send('not recorded');
  f.context.navigator.sendBeacon('/telemetry', 'not recorded');
  const requests = f.messages.filter(event => event.action === 'request');
  assert.ok(requests.some(event => event.transport === 'fetch' && event.url === 'https://claude.ai/api/event_logging/v2/batch'));
  assert.ok(requests.some(event => event.transport === 'xhr' && event.url === 'https://claude.ai/collect'));
  assert.ok(requests.some(event => event.transport === 'beacon' && event.url === 'https://claude.ai/telemetry'));
});

test('fetch observation does not invoke method accessors before the native call', async () => {
  let methodReads = 0;
  const init = {get method() { methodReads++; return 'POST'; }};
  const f = fixture({readMethod:true});
  await f.context.fetch('/collect', init);
  assert.equal(methodReads, 1, 'Only the original fetch implementation should evaluate an accessor');
});

test('XHR return values and payload ownership stay with the original implementation', () => {
  const f = fixture();
  const xhr = new f.context.XMLHttpRequest();
  const body = new Uint8Array([1,2,3]);
  assert.equal(xhr.open('POST', 'https://claude.ai/collect'), f.returnValue);
  assert.equal(xhr.send(body), f.returnValue);
  xhr.finish();
  const send = f.calls.find(call => call.kind === 'send');
  assert.equal(send.args[0], body);
  assert.equal(send.receiver, xhr);
  assert.ok(f.messages.some(event => event.transport === 'xhr' && event.action === 'response'));
});

test('an unavailable observation listener cannot prevent the XHR request', () => {
  const f = fixture({brokenListener:true});
  const xhr = new f.context.XMLHttpRequest();
  xhr.open('POST', '/collect');
  assert.equal(xhr.send('BODY'), f.returnValue);
  assert.equal(f.calls.filter(call => call.kind === 'send').length, 1);
});

test('unreadable body metadata cannot prevent transport calls', () => {
  const f = fixture();
  class HostileBuffer extends ArrayBuffer {
    get byteLength() { throw new Error('No observational metadata'); }
  }
  const body = new HostileBuffer(4);
  const xhr = new f.context.XMLHttpRequest();
  xhr.open('POST', '/collect');
  assert.equal(xhr.send(body), f.returnValue);
  assert.equal(f.context.navigator.sendBeacon('/collect', body), true);
  assert.equal(new f.context.WebSocket().send(body), f.returnValue);
  assert.equal(f.calls.filter(call => ['send','beacon','socket'].includes(call.kind)).length, 3);
});

test('beacon and WebSocket preserve native results and never forward message text', () => {
  const f = fixture({beaconResult:false});
  const secret = 'SENSITIVE_MESSAGE_12345';
  assert.equal(f.context.navigator.sendBeacon('https://claude.ai/collect', secret), false);
  const socket = new f.context.WebSocket();
  assert.equal(socket.send(secret), f.returnValue);
  assert.equal(f.calls.find(call => call.kind === 'socket').args[0], secret);
  assert.ok(!JSON.stringify(f.messages).includes(secret));
  assert.ok(f.messages.some(event => event.action === 'beacon_queued' && event.phase === 'rejected'));
});

test('device and sensitive API observation contains field names without returned values', () => {
  const f = fixture();
  assert.equal(f.context.navigator.userAgent, 'private-device-value');
  assert.equal(f.context.navigator.userAgent, 'private-device-value');
  assert.equal(f.getterReads(), 2);
  assert.equal(f.messages.filter(event => event.action === 'device_read' && event.field === 'userAgent').length, 1);
  const callback = () => {};
  assert.equal(f.context.navigator.geolocation.getCurrentPosition(callback), f.returnValue);
  assert.equal(f.context.navigator.geolocation.watchPosition(callback), 17);
  assert.equal(f.context.navigator.clipboard.readText(), f.promise);
  assert.equal(f.context.navigator.mediaDevices.getUserMedia({audio:true}), f.promise);
  const canvas = new f.context.HTMLCanvasElement();
  assert.equal(canvas.toDataURL(), 'private-canvas-result');
  assert.ok(!JSON.stringify(f.messages).includes('private-device-value'));
  assert.ok(!JSON.stringify(f.messages).includes('private-canvas-result'));
});

test('document-start injection is idempotent', async () => {
  const f = fixture();
  const fetch = f.context.fetch, sendBeacon = f.context.navigator.sendBeacon;
  f.run();
  assert.equal(f.context.fetch, fetch);
  assert.equal(f.context.navigator.sendBeacon, sendBeacon);
  assert.equal(f.messages.filter(event => event.action === 'installed').length, 1);
  await f.context.fetch('https://claude.ai/check');
  assert.equal(f.messages.filter(event => event.action === 'request').length, 1);
});

test('a missing bridge does not mark an uninstalled observer as installed', () => {
  const f = fixture({noBridge:true});
  f.context.fkclaudeAuditBridge = {postMessage(value) { f.messages.push(JSON.parse(value)); }};
  f.run();
  assert.equal(f.messages.filter(event => event.action === 'installed').length, 1);
});

test('bridge event rate is bounded and dropped messages are reported next window', () => {
  const f = fixture();
  for (let i = 0; i < 1000; i++) assert.equal(f.context.navigator.sendBeacon('/collect', 'BODY'), true);
  assert.ok(f.messages.length <= 120, 'One document cannot flood the native bridge');
  assert.equal(f.calls.filter(call => call.kind === 'beacon').length, 1000);
  f.setClock(1001);
  f.context.navigator.sendBeacon('/collect', 'BODY');
  assert.ok(f.messages.some(event => event.action === 'dropped' && event.count > 0));
});

test('observer response metadata failures do not create unhandled promise rejections', () => {
  const child = `
    import vm from 'node:vm';
    import {readFileSync} from 'node:fs';
    const original = Promise.resolve({get status(){throw new Error('metadata unavailable');}});
    const context = vm.createContext({Promise,URL,location:{href:'https://claude.ai/'},
      fkclaudeAuditBridge:{postMessage(){}},fetch(){return original;}});
    vm.runInContext(readFileSync(${JSON.stringify(scriptPath)},'utf8'), context);
    if (context.fetch('/collect') !== original) throw new Error('Promise identity changed');
    await original;
    await new Promise(resolve => setImmediate(resolve));
  `;
  const result = spawnSync(process.execPath, ['--unhandled-rejections=strict','--input-type=module','-e',child],
    {encoding:'utf8',timeout:5000});
  assert.equal(result.status, 0, result.stderr);
});
