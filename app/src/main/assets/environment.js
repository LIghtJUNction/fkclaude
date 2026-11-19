(function(c) {
  'use strict';
  const own = k => Object.prototype.hasOwnProperty.call(c, k);
  const on = k => c[k] === 'true';
  const number = k => Number(c[k]);
  const patch = (o, k, descriptor) => {
    let owner = o;
    while (owner && !Object.prototype.hasOwnProperty.call(owner, k)) owner = Object.getPrototypeOf(owner);
    owner = owner || o;
    const previous = Object.getOwnPropertyDescriptor(owner, k);
    const next = {...descriptor, configurable:true, enumerable:!!(previous && previous.enumerable)};
    try { Object.defineProperty(owner, k, next); }
    catch (_) { try { Object.defineProperty(o, k, next); } catch (_) {} }
  };
  const define = (o, k, v) => patch(o, k, {get: () => v});
  const replace = (o, k, v) => patch(o, k, {value:v, writable:true});
  if (typeof navigator !== 'undefined') {
    if (own('locale')) {
      define(navigator, 'language', c.locale);
      define(navigator, 'languages', Object.freeze([c.locale]));
    }
    for (const [key, name] of [['web_platform','platform'], ['web_vendor','vendor'], ['user_agent','userAgent']])
      if (own(key)) define(navigator, name, c[key]);
    if (own('user_agent')) define(navigator, 'appVersion', c.user_agent.replace(/^Mozilla\//, ''));
    if (own('cpu_count')) define(navigator, 'hardwareConcurrency', number('cpu_count'));
    if (own('web_device_memory') || own('memory_total'))
      define(navigator, 'deviceMemory', own('web_device_memory') ? number('web_device_memory') : number('memory_total') / 1073741824);
    if (on('block_web_fingerprinting') || own('user_agent') || own('build.MODEL') || own('os_release')) {
      const old = navigator.userAgentData;
      const major = (c.user_agent || '').match(/Chrom(?:e|ium)\/(\d+)/);
      const version = (c.user_agent || '').match(/Chrom(?:e|ium)\/(\d+(?:\.\d+){3})/);
      const brands = Object.freeze(own('user_agent') ? (major ? [Object.freeze({brand:'Chromium', version:major[1]})] : []) :
        Array.from(old && old.brands || [], b => Object.freeze({brand:b.brand, version:b.version})));
      const mobile = own('user_agent') ? /Mobile|Android/.test(c.user_agent) : !!(old && old.mobile);
      const platform = own('web_platform') ? c.web_platform : 'Android';
      const low = () => ({brands, mobile, platform});
      const entropy = {
        architecture: own('web_architecture') ? c.web_architecture : (own('native_machine') && /x86/.test(c.native_machine) ? 'x86' : 'arm'),
        bitness: own('web_bitness') ? c.web_bitness : (own('native_machine') && /64/.test(c.native_machine) ? '64' : ''),
        model: c['build.MODEL'] || '', platformVersion: own('web_platform_version') ? c.web_platform_version : (c.os_release || ''),
        uaFullVersion: version ? version[1] : '',
        fullVersionList: own('user_agent') ? Object.freeze(version
          ? [Object.freeze({brand:'Chromium', version:version[1]})] : []) : brands,
        wow64: false, formFactors: Object.freeze(['Mobile'])
      };
      const data = {...low(), toJSON:low, getHighEntropyValues: async hints => {
        const result = low(); for (const key of hints || []) if (Object.prototype.hasOwnProperty.call(entropy, key)) result[key] = entropy[key];
        return result;
      }};
      define(navigator, 'userAgentData', Object.freeze(data));
    }
    if (on('block_location') || own('latitude') || own('longitude')) {
      const answer = (ok, error, active = () => true) => queueMicrotask(() => {
        if (!active()) return;
        if (on('block_location')) { if (typeof error === 'function') error({code:1, message:'', PERMISSION_DENIED:1}); }
        else if (typeof ok === 'function') ok({timestamp:Date.now(), coords:{latitude:number('latitude') || 0,
          longitude:number('longitude') || 0, accuracy:number('location_accuracy') || 0, altitude:null,
          altitudeAccuracy:null, heading:null, speed:null}});
      });
      let watch = 0;
      const pending = new Set();
      define(navigator, 'geolocation', Object.freeze({
        getCurrentPosition:(ok, error) => answer(ok, error),
        watchPosition:(ok, error) => {
          const id = ++watch; pending.add(id);
          answer(ok, error, () => pending.delete(id));
          return id;
        },
        clearWatch:id => { pending.delete(id); }
      }));
      if (on('block_location') && navigator.permissions && typeof navigator.permissions.query === 'function') {
        const originalQuery = navigator.permissions.query;
        replace(navigator.permissions, 'query', function(descriptor) {
          if (descriptor && descriptor.name === 'geolocation') {
            const status = typeof EventTarget === 'function' ? new EventTarget() : {};
            Object.defineProperties(status, {state:{value:'denied', enumerable:true}, onchange:{value:null, writable:true}});
            return Promise.resolve(status);
          }
          return Reflect.apply(originalQuery, this, [descriptor]);
        });
      }
    }
    if (on('block_web_fingerprinting')) {
      define(navigator, 'gpu', undefined);
      define(navigator, 'plugins', Object.freeze([])); define(navigator, 'mimeTypes', Object.freeze([]));
      if (navigator.mediaDevices) replace(navigator.mediaDevices, 'enumerateDevices', async () => []);
    }
  }
  if (typeof screen !== 'undefined') {
    const ratio = own('screen_density') ? number('screen_density') : (globalThis.devicePixelRatio || 1);
    if (own('screen_density')) define(globalThis, 'devicePixelRatio', ratio);
    if (own('screen_width')) for (const key of ['width','availWidth']) define(screen, key, Math.round(number('screen_width') / ratio));
    if (own('screen_height')) for (const key of ['height','availHeight']) define(screen, key, Math.round(number('screen_height') / ratio));
  }
  if (own('timezone') || own('locale')) {
    const OriginalFormat = Intl.DateTimeFormat;
    const formatOptions = options => {
      if (options === null) return null;
      const copy = Object.create(options === undefined ? null : Object(options));
      if (own('timezone') && (options === undefined || options.timeZone === undefined))
        Object.defineProperty(copy, 'timeZone', {value:c.timezone, enumerable:true});
      return copy;
    };
    const locales = value => value === undefined && own('locale') ? c.locale : value;
    const Format = new Proxy(OriginalFormat, {
      construct:(target, args) => Reflect.construct(target, [locales(args[0]), formatOptions(args[1])]),
      apply:(target, self, args) => Reflect.apply(target, self, [locales(args[0]), formatOptions(args[1])])
    });
    replace(Intl, 'DateTimeFormat', Format);
    replace(OriginalFormat.prototype, 'constructor', Format);
    if (own('timezone')) {
      const RealDate = Date;
      const format = new OriginalFormat('en-US-u-ca-iso8601-nu-latn', {timeZone:c.timezone, hourCycle:'h23',
        year:'numeric', month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit', second:'2-digit'});
      const parts = date => {
        if (!Number.isFinite(date.getTime())) return null;
        const p = {}; for (const part of format.formatToParts(date)) if (part.type !== 'literal') p[part.type] = Number(part.value);
        return p;
      };
      const offset = date => { const p = parts(date); return p ? (Math.floor(date.getTime()/1000)*1000 -
        RealDate.UTC(p.year,p.month-1,p.day,p.hour%24,p.minute,p.second)) / 60000 : NaN; };
      replace(RealDate.prototype, 'getTimezoneOffset', function() { return offset(this); });
      for (const [name, key, adjust] of [['getFullYear','year',0],['getYear','year',-1900],['getMonth','month',-1],
        ['getDate','day',0],['getHours','hour',0],['getMinutes','minute',0],['getSeconds','second',0]])
        replace(RealDate.prototype, name, function() { const p = parts(this); return p ? p[key] + adjust : NaN; });
      replace(RealDate.prototype, 'getDay', function() { const p = parts(this); return p ? new RealDate(RealDate.UTC(p.year,p.month-1,p.day)).getUTCDay() : NaN; });
      const string = date => {
        if (!parts(date)) return 'Invalid Date';
        const display = new OriginalFormat('en-US', {timeZone:c.timezone, weekday:'short', month:'short', day:'2-digit', year:'numeric'}).formatToParts(date);
        const p = {}; for (const v of display) p[v.type] = v.value;
        const n = parts(date), o = -offset(date), absolute = Math.abs(o), pad = v => String(v).padStart(2,'0');
        return p.weekday+' '+p.month+' '+p.day+' '+p.year+' '+pad(n.hour%24)+':'+pad(n.minute)+':'+pad(n.second)+
          ' GMT'+(o >= 0 ? '+' : '-')+pad(Math.floor(absolute/60))+pad(absolute%60)+' ('+c.timezone+')';
      };
      replace(RealDate.prototype, 'toString', function() { return string(this); });
      replace(RealDate.prototype, 'toDateString', function() { return string(this).split(' ').slice(0,4).join(' '); });
      replace(RealDate.prototype, 'toTimeString', function() { return parts(this) ? string(this).split(' ').slice(4).join(' ') : 'Invalid Date'; });
      for (const method of ['toLocaleString','toLocaleDateString','toLocaleTimeString']) {
        const original = RealDate.prototype[method];
        replace(RealDate.prototype, method, function(locale, options) {
          return Reflect.apply(original, this, [locales(locale), formatOptions(options)]);
        });
      }
      const VirtualDate = new Proxy(RealDate, {apply:() => string(new RealDate())});
      replace(RealDate.prototype, 'constructor', VirtualDate);
      replace(globalThis, 'Date', VirtualDate);
    }
  }
  if (on('block_web_fingerprinting')) {
    for (const name of ['HTMLCanvasElement','OffscreenCanvas']) if (typeof globalThis[name] === 'function') {
      replace(globalThis[name].prototype, 'getContext', () => null);
      if (name === 'HTMLCanvasElement') {
        replace(globalThis[name].prototype, 'toDataURL', () => 'data:,');
        replace(globalThis[name].prototype, 'toBlob', callback => queueMicrotask(() => callback(null)));
      }
    }
    for (const name of ['AudioContext','webkitAudioContext','OfflineAudioContext','webkitOfflineAudioContext'])
      if (typeof globalThis[name] !== 'undefined') replace(globalThis, name, undefined);
  }
})(__FKCLAUDE_CONFIG__);
