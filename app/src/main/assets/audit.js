(() => {
  "use strict";
  const apply = Reflect.apply;
  const descriptor = Object.getOwnPropertyDescriptor;
  const prototype = Object.getPrototypeOf;
  let bridge;
  try {
    bridge = globalThis.fkclaudeAuditBridge;
    if (!bridge || typeof bridge.postMessage !== "function") return;
    if (descriptor(globalThis, "__fkclaudeAuditInstalled")?.value === true) return;
    Object.defineProperty(globalThis, "__fkclaudeAuditInstalled", {value: true, configurable: false});
  } catch (_) { return; }

  const documentId = (() => {
    const bytes = new Uint8Array(16);
    try { globalThis.crypto.getRandomValues(bytes); }
    catch (_) { for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256); }
    return "d" + Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("");
  })();
  let sequence = 0, budgetStart = Date.now(), budget = 0, dropped = 0;
  const ids = new WeakMap(), seenFields = new Set();
  const emit = (action, data = {}) => {
    try {
      const now = Date.now();
      if (now - budgetStart >= 1000) {
        if (dropped) bridge.postMessage(JSON.stringify({action:"dropped",document_id:documentId,count:dropped}));
        budgetStart = now; budget = 0; dropped = 0;
      }
      if (++budget > 120) { dropped++; return; }
      bridge.postMessage(JSON.stringify({action,document_id:documentId,...data}));
    } catch (_) {}
  };
  const reference = () => "w" + (++sequence);
  const getter = (owner, name) => {
    try { return owner && descriptor(owner, name)?.get; } catch (_) { return undefined; }
  };
  const ownData = (owner, name) => {
    try {
      const property = owner && descriptor(owner, name);
      return property && Object.hasOwn(property, "value") ? property.value : undefined;
    } catch (_) { return undefined; }
  };
  const method = (owner, name) => {
    try {
      for (let current = owner; current; current = prototype(current)) {
        const property = descriptor(current, name);
        if (property) return typeof property.value === "function" ? property.value : undefined;
      }
    } catch (_) {}
    return undefined;
  };
  const readProperty = (owner, name) => { try { return owner && owner[name]; } catch (_) { return undefined; } };
  const wrap = (owner, name, build) => {
    try {
      const original = method(owner, name);
      if (!original) return;
      const property = descriptor(owner, name);
      Object.defineProperty(owner, name, {value:build(original),configurable:true,writable:true,
        enumerable:property ? property.enumerable : false});
    } catch (_) {}
  };

  const NativeURL = typeof URL !== "undefined" ? URL : undefined;
  const urlHref = NativeURL && getter(NativeURL.prototype, "href");
  const NativeRequest = typeof Request !== "undefined" ? Request : undefined;
  const requestUrl = NativeRequest && getter(NativeRequest.prototype, "url");
  const requestMethod = NativeRequest && getter(NativeRequest.prototype, "method");
  const responseStatus = typeof Response !== "undefined" && getter(Response.prototype, "status");
  const promiseThen = Promise.prototype.then;
  const byteGetters = [
    getter(ArrayBuffer.prototype, "byteLength"),
    typeof Uint8Array !== "undefined" && getter(prototype(Uint8Array.prototype), "byteLength"),
    typeof DataView !== "undefined" && getter(DataView.prototype, "byteLength"),
    typeof Blob !== "undefined" && getter(Blob.prototype, "size"),
  ].filter(value => typeof value === "function");
  const byteLength = value => {
    if (typeof value === "string" || value == null) return -1;
    for (const get of byteGetters) {
      try {
        const size = apply(get, value, []);
        if (typeof size === "number" && Number.isFinite(size)) return size;
      } catch (_) {}
    }
    return -1;
  };
  const urlOf = value => {
    try {
      let raw = typeof value === "string" ? value : "";
      if (!raw && value != null && typeof value !== "string") {
        for (const get of [urlHref, requestUrl]) {
          if (typeof get !== "function") continue;
          try { raw = apply(get, value, []); break; } catch (_) {}
        }
        // Test adapters and non-browser Request implementations may expose plain data.
        if (!raw && NativeRequest && value instanceof NativeRequest) raw = ownData(value, "url") || "";
      }
      if (typeof raw !== "string" || !raw || !NativeURL) return "";
      return new NativeURL(raw, typeof location !== "undefined" ? location.href : undefined).href;
    } catch (_) { return ""; }
  };
  const fetchMethod = (input, init) => {
    let value = "GET";
    if (requestMethod && input != null) {
      try { value = apply(requestMethod, input, []); } catch (_) {}
    } else if (NativeRequest && input instanceof NativeRequest) {
      value = typeof ownData(input, "method") === "string" ? ownData(input, "method") : "UNKNOWN";
    }
    if (init != null) {
      try {
        const property = descriptor(init, "method");
        if (property) return typeof property.value === "string" ? property.value : "UNKNOWN";
        const parent = prototype(init);
        if (parent != null && parent !== Object.prototype
            && descriptor(parent, "method")) return "UNKNOWN";
      } catch (_) { return "UNKNOWN"; }
    }
    return value;
  };
  const statusOf = (value, nativeGetter) => {
    if (nativeGetter) {
      try { const status = apply(nativeGetter, value, []); if (typeof status === "number") return status; }
      catch (_) {}
    }
    const status = ownData(value, "status");
    return typeof status === "number" ? status : undefined;
  };

  wrap(globalThis, "fetch", original => function(input, init) {
    const data = {id:reference(),url:"",method:"UNKNOWN",transport:"fetch"};
    try { data.url = urlOf(input); data.method = fetchMethod(input, init); emit("request", {...data,phase:"attempt"}); }
    catch (_) {}
    let promise;
    try { promise = apply(original, this, arguments); }
    catch (error) { emit("failure",data); throw error; }
    try {
      // Return the original promise. Neither response streams nor body methods are touched.
      apply(promiseThen, promise, [
        response => { try { emit("response",{...data,status:statusOf(response,responseStatus)}); } catch (_) {} },
        () => { emit("failure",data); },
      ]);
    } catch (_) { /* Non-Promise custom fetch results pass through unchanged. */ }
    return promise;
  });

  if (typeof XMLHttpRequest !== "undefined") {
    const xhrStatus = getter(XMLHttpRequest.prototype, "status");
    wrap(XMLHttpRequest.prototype, "open", original => function(verb, url) {
      const result = apply(original, this, arguments);
      try { ids.set(this,{id:reference(),url:urlOf(url),method:typeof verb === "string" ? verb : "UNKNOWN",transport:"xhr"}); }
      catch (_) {}
      return result;
    });
    wrap(XMLHttpRequest.prototype, "send", original => function(body) {
      let data = {id:reference(),url:"",method:"UNKNOWN",transport:"xhr"}, listener;
      try {
        data = ids.get(this) || data;
        emit("request",{...data,phase:"attempt",bytes:byteLength(body)});
        listener = () => {
          try {
            const status = statusOf(this,xhrStatus);
            emit(status ? "response" : "failure",{...data,status});
          } catch (_) {}
        };
        const add = method(this,"addEventListener");
        if (add) apply(add,this,["loadend",listener,{once:true}]);
      } catch (_) {}
      try { return apply(original, this, arguments); }
      catch (error) {
        emit("failure",data);
        try {
          const remove = method(this,"removeEventListener");
          if (remove && listener) apply(remove,this,["loadend",listener]);
        } catch (_) {}
        throw error;
      }
    });
  }

  if (typeof navigator !== "undefined") {
    wrap(navigator, "sendBeacon", original => function(url, body) {
      let data = {id:reference(),url:"",method:"POST",transport:"beacon"};
      try { data = {...data,url:urlOf(url),bytes:byteLength(body)}; emit("request",{...data,phase:"attempt"}); }
      catch (_) {}
      let accepted;
      try { accepted = apply(original,this,arguments); }
      catch (error) { emit("failure",data); throw error; }
      emit("beacon_queued",{...data,phase:accepted === true ? "accepted" : "rejected"});
      return accepted;
    });
    for (const name of ["userAgent","platform","vendor","language","languages","hardwareConcurrency","deviceMemory","maxTouchPoints"]) {
      try {
        let owner = navigator, property;
        while (owner && !(property = descriptor(owner,name))) owner = prototype(owner);
        if (!property || typeof property.get !== "function") continue;
        const get = property.get;
        Object.defineProperty(navigator,name,{configurable:true,enumerable:property.enumerable,get:function() {
          if (!seenFields.has(name)) { seenFields.add(name); emit("device_read",{field:name}); }
          return apply(get,this,[]);
        }});
      } catch (_) {}
    }
    for (const name of ["getCurrentPosition","watchPosition"]) wrap(readProperty(navigator,"geolocation"),name,original=>function() {
      emit("sensitive_api",{field:"geolocation."+name}); return apply(original,this,arguments);
    });
    for (const name of ["read","readText"]) wrap(readProperty(navigator,"clipboard"),name,original=>function() {
      emit("sensitive_api",{field:"clipboard."+name}); return apply(original,this,arguments);
    });
    for (const name of ["enumerateDevices","getUserMedia"]) wrap(readProperty(navigator,"mediaDevices"),name,original=>function() {
      emit("sensitive_api",{field:"mediaDevices."+name}); return apply(original,this,arguments);
    });
  }
  if (typeof WebSocket !== "undefined") {
    const socketUrl = getter(WebSocket.prototype,"url");
    wrap(WebSocket.prototype,"send",original=>function(body) {
      const data = {id:reference(),url:"",method:"FRAME",transport:"websocket"};
      try {
        let raw;
        try { raw = socketUrl ? apply(socketUrl,this,[]) : ownData(this,"url"); } catch (_) {}
        data.url = urlOf(raw); data.bytes = byteLength(body);
        emit("request",{...data,phase:"attempt"});
      } catch (_) {}
      try { return apply(original,this,arguments); }
      catch (error) { emit("failure",data); throw error; }
    });
  }
  if (typeof HTMLCanvasElement !== "undefined") for (const name of ["toDataURL","toBlob"]) wrap(HTMLCanvasElement.prototype,name,original=>function() {
    emit("device_read",{field:"canvas."+name}); return apply(original,this,arguments);
  });
  if (typeof CanvasRenderingContext2D !== "undefined") wrap(CanvasRenderingContext2D.prototype,"getImageData",original=>function() {
    emit("device_read",{field:"canvas.getImageData"}); return apply(original,this,arguments);
  });
  emit("installed",{phase:"document_start"});
})();
