import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const source = readFileSync(new URL('../app/src/main/assets/environment.js', import.meta.url), 'utf8');
function environment(config = {}) {
  const realm = vm.createContext({queueMicrotask});
  vm.runInContext(`
    globalThis.Navigator=class {};
    Object.assign(Navigator.prototype, {language:'real', languages:['real'], platform:'real', vendor:'real', userAgent:'real',
      hardwareConcurrency:19, deviceMemory:31, gpu:{real:true}, plugins:['real'], mimeTypes:['real'],
      mediaDevices:{enumerateDevices:async()=>['real-device']},
      permissions:{query:async descriptor=>{if(descriptor.name==='geolocation') throw Error('native permission queried');return {state:'prompt'}}},
      userAgentData:{brands:[{brand:'Real',version:'999'}],mobile:true,getHighEntropyValues:()=>{throw Error('real entropy requested')}},
      geolocation:{getCurrentPosition:()=>{throw Error('real location requested')}}});
    globalThis.navigator=new Navigator();
    globalThis.screen={width:999,height:999,availWidth:999,availHeight:999};
    globalThis.devicePixelRatio=1;
    globalThis.HTMLCanvasElement=class {getContext(){return {real:true}} toDataURL(){return 'real-canvas'}};
    globalThis.OffscreenCanvas=class {getContext(){return {real:true}}};
    globalThis.AudioContext=class {};
    globalThis.OfflineAudioContext=class {};
  `, realm);
  vm.runInContext(source.replace('__FKCLAUDE_CONFIG__', JSON.stringify(config)), realm);
  return expression => vm.runInContext(expression, realm);
}

test('unconfigured environment preserves ordinary APIs', async () => {
  const run = environment();
  assert.equal(run('navigator.hardwareConcurrency'), 19);
  assert.equal(run('navigator.language'), 'real');
  assert.equal(run('new HTMLCanvasElement().getContext("2d").real'), true);
  assert.equal((await run('navigator.mediaDevices.enumerateDevices()'))[0], 'real-device');
});

test('configured navigator, screen and client hints agree without real entropy requests', async () => {
  const run = environment({locale:'zh-CN',user_agent:'Mozilla/5.0 Android Mobile Chrome/140.0.0.0',
    cpu_count:'4',memory_total:'4294967296',web_platform:'Linux armv8l',web_vendor:'custom',
    screen_width:'1080',screen_height:'2400',screen_density:'3','build.MODEL':'Virtual',os_release:'16',native_machine:'aarch64'});
  assert.equal(run('navigator.language'), 'zh-CN');
  assert.equal(run('navigator.hardwareConcurrency'), 4);
  assert.equal(run('Object.getOwnPropertyDescriptor(Navigator.prototype,"hardwareConcurrency").get.call(navigator)'), 4);
  assert.equal(run('navigator.deviceMemory'), 4);
  assert.equal(run('navigator.vendor'), 'custom');
  assert.equal(run('screen.width'), 360); assert.equal(run('screen.availHeight'), 800);
  assert.equal(run('devicePixelRatio'), 3);
  const hints = await run('navigator.userAgentData.getHighEntropyValues(["model","platformVersion","architecture","bitness","unknown"])');
  assert.equal(hints.model, 'Virtual'); assert.equal(hints.platformVersion, '16');
  assert.equal(hints.architecture, 'arm'); assert.equal(hints.bitness, '64'); assert.equal(hints.unknown, undefined);
  assert.equal(run('Object.isFrozen(navigator.languages)'), true);
});

test('timezone readers cover DST, defaults, inherited options and explicit timezone', () => {
  const run = environment({timezone:'America/New_York',locale:'en-US'});
  assert.equal(run('new Date("2026-07-01T00:00:00Z").getTimezoneOffset()'), 240);
  assert.equal(run('new Date("2026-01-01T00:00:00Z").getTimezoneOffset()'), 300);
  assert.equal(run('new Date("2026-07-01T00:00:00Z").getHours()'), 20);
  assert.equal(run('new Date("2026-07-01T00:00:00Z").getDate()'), 30);
  assert.match(run('new Date("2026-07-01T00:00:00Z").toString()'), /20:00:00 GMT-0400/);
  assert.match(run('new Date("2026-07-01T00:00:00Z").toLocaleTimeString()'), /8:00:00/);
  assert.match(run('new Date("2026-07-01T00:00:00Z").toLocaleString()'), /6\/30\/2026/);
  assert.equal(run('new Intl.DateTimeFormat().resolvedOptions().timeZone'), 'America/New_York');
  assert.equal(run('new Intl.DateTimeFormat(undefined,Object.create({timeZone:"UTC"})).resolvedOptions().timeZone'), 'UTC');
  assert.equal(run('new Date().constructor === Date'), true);
  assert.equal(run('new Intl.DateTimeFormat().constructor === Intl.DateTimeFormat'), true);
  assert.equal(run('new Date("invalid").toTimeString()'), 'Invalid Date');
  assert.equal(run('Number.isNaN(new Date("invalid").getHours())'), true);
  assert.equal(run('new Date("2026-07-01T00:00:00Z").toISOString()'), '2026-07-01T00:00:00.000Z');
  const fractional = environment({timezone:'Asia/Kathmandu'});
  assert.equal(fractional('new Date("2026-07-01T00:00:00Z").getTimezoneOffset()'), -345);
});

test('optional fingerprint blocking replaces canvas, audio, GPU and device lists', async () => {
  const run = environment({block_web_fingerprinting:'true'});
  assert.equal(run('new HTMLCanvasElement().getContext("webgl")'), null);
  assert.equal(run('new OffscreenCanvas().getContext("2d")'), null);
  assert.equal(run('new HTMLCanvasElement().toDataURL()'), 'data:,');
  assert.equal(run('AudioContext'), undefined); assert.equal(run('navigator.gpu'), undefined);
  assert.equal(run('navigator.plugins.length'), 0);
  assert.equal((await run('navigator.mediaDevices.enumerateDevices()')).length, 0);
  assert.equal(await run('new Promise(resolve=>new HTMLCanvasElement().toBlob(resolve))'), null);
});

test('location blocking and custom locations never invoke the original provider', async () => {
  const denied = environment({block_location:'true'});
  assert.equal(await denied('new Promise(resolve=>navigator.geolocation.getCurrentPosition(()=>resolve("unexpected"),e=>resolve(e.code)))'), 1);
  const custom = environment({latitude:'12.5',longitude:'-30',location_accuracy:'5'});
  const position = await custom('new Promise(resolve=>navigator.geolocation.getCurrentPosition(resolve))');
  assert.equal(position.coords.latitude, 12.5); assert.equal(position.coords.longitude, -30);
});

test('clearing a geolocation watch cancels its queued result', async () => {
  for (const config of [{block_location:'true'}, {latitude:'40.7',longitude:'-74'}]) {
    const run = environment(config);
    run('globalThis.callbacks=0; const id=navigator.geolocation.watchPosition(()=>callbacks++,()=>callbacks++); navigator.geolocation.clearWatch(id)');
    await new Promise(resolve => queueMicrotask(resolve));
    assert.equal(run('callbacks'), 0);
  }
});

test('location denial is asynchronous and explicit permission queries agree', async () => {
  const run = environment({block_location:'true'});
  run('globalThis.result="pending"; navigator.geolocation.getCurrentPosition(()=>result="unexpected",error=>result=error.code)');
  assert.equal(run('result'), 'pending');
  await new Promise(resolve => queueMicrotask(resolve));
  assert.equal(run('result'), 1);
  assert.equal((await run('navigator.permissions.query({name:"geolocation"})')).state, 'denied');
  assert.equal((await run('navigator.permissions.query({name:"notifications"})')).state, 'prompt');
});

test('live geolocation watch completes once without invoking the native provider', async () => {
  const run = environment({block_location:'true'});
  run('globalThis.callbacks=0; globalThis.code=0; navigator.geolocation.watchPosition(()=>callbacks+=100,error=>{callbacks++;code=error.code})');
  await new Promise(resolve => queueMicrotask(resolve));
  assert.equal(run('callbacks'), 1);
  assert.equal(run('code'), 1);
});

test('Pixel projection retains the installed engine version in full client hints', async () => {
  const ua = 'Mozilla/5.0 (Linux; Android 16; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36';
  const run = environment({locale:'en-US', user_agent:ua, web_platform:'Android', web_vendor:'Google Inc.', 'build.MODEL':'Pixel 8',
    web_platform_version:'16', web_architecture:'', web_bitness:'', os_release:'untrusted', native_machine:'untrusted'});
  assert.equal(run('navigator.userAgent'), ua);
  assert.equal(run('navigator.platform'), 'Android');
  const hints = await run('navigator.userAgentData.getHighEntropyValues(["model","uaFullVersion","fullVersionList","platformVersion","architecture","bitness"])');
  assert.equal(hints.model, 'Pixel 8');
  assert.equal(hints.uaFullVersion, '141.0.7390.122');
  assert.equal(hints.platformVersion, '16');
  assert.equal(hints.architecture, '');
  assert.equal(hints.bitness, '');
  assert.equal(hints.fullVersionList[0].version, '141.0.7390.122');
  assert.equal(hints.brands[0].version, '141');
  assert.equal(hints.platform, 'Android');
});
