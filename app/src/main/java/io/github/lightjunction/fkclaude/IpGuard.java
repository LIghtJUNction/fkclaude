package io.github.lightjunction.fkclaude;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Process-local, fail-closed egress lease. Probes use Claude's UID and ordinary default route. */
final class IpGuard {
    private static final ThreadLocal<Boolean> PROBE = ThreadLocal.withInitial(() -> false);
    private static volatile IpGuard instance;
    private final Object lock = new Object();
    private final ConnectivityManager connectivity;
    private final IpAllowlist allowlist;
    private final URI endpoint;
    private final int interval;
    private final int leaseMillis;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor timeouts;
    private long generation, observedNetwork;
    private volatile long ipv4Until, ipv6Until;
    private String lastStatus = "";
    private SSLSocketFactory probeTls;

    private IpGuard(Context context, Profile profile) {
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) throw new SecurityException("Network state permission unavailable");
        connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) throw new IllegalStateException("Network state unavailable");
        allowlist = new IpAllowlist(profile.get("ip_guard_cidrs", ""));
        endpoint = ProbeResponse.endpoint(profile.get("ip_guard_probe_url", IpGuardDefaults.PROBE));
        interval = (int) Long.parseLong(profile.get("ip_guard_interval", "3"));
        leaseMillis = Math.min(10000, interval * 2000 + 4000);
        executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "fkclaude-ip-guard"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        timeouts = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "fkclaude-ip-timeout"); thread.setDaemon(true); return thread;
        });
        timeouts.setRemoveOnCancelPolicy(true);
    }

    // The caller runs under Claude's UID; its permission is checked by the constructor above.
    @android.annotation.SuppressLint("MissingPermission")
    static synchronized void install(Context context, Profile profile) {
        if (!profile.flag("ip_guard_enabled") || instance != null) return;
        try {
            IpGuard guard = new IpGuard(context, profile);
            System.loadLibrary("fkclaude");
            if (!nativeStart()) throw new IllegalStateException("Native egress gate unavailable");
            instance = guard;
            installJavaGates();
            guard.connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { guard.invalidate(); }
                @Override public void onLost(Network network) { guard.invalidate(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { guard.invalidate(); }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { guard.invalidate(); }
            });
            guard.executor.scheduleWithFixedDelay(guard::requestCheck, 0, guard.interval, TimeUnit.SECONDS);
            XposedBridge.log("fkclaude: IP guard enabled; initially blocked; allowlist empty=" + guard.allowlist.isEmpty());
        } catch (Throwable error) {
            // An explicitly enabled guard must never silently degrade into an unguarded app.
            XposedBridge.log("fkclaude: IP guard cannot enforce; terminating target (" + error.getClass().getSimpleName() + ")");
            android.os.Process.killProcess(android.os.Process.myPid());
            throw new IllegalStateException("IP guard enforcement failed");
        }
    }

    private static void installJavaGates() {
        XC_MethodHook gate = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!permitted()) param.setThrowable(new SocketException("EACCES (Permission denied)"));
            }
        };
        XC_MethodHook dns = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!permitted()) param.setThrowable(new java.net.UnknownHostException("EAI_AGAIN"));
            }
        };
        for (String name : new String[]{"getByName", "getAllByName"})
            if (XposedBridge.hookAllMethods(InetAddress.class, name, dns).isEmpty())
                throw new IllegalStateException("DNS gate unavailable");
        if (XposedBridge.hookAllMethods(Socket.class, "connect", gate).isEmpty())
            throw new IllegalStateException("Socket gate unavailable");
        XposedBridge.hookAllMethods(java.net.DatagramSocket.class, "send", gate);
        XposedBridge.hookAllMethods(java.net.DatagramSocket.class, "connect", gate);
    }

    private static boolean permitted() {
        if (PROBE.get()) return true;
        IpGuard guard = instance;
        if (guard == null) return true;
        long now = SystemClock.elapsedRealtime();
        return guard.ipv4Until > now || guard.ipv6Until > now;
    }

    private void invalidate() {
        synchronized (lock) { generation++; ipv4Until = ipv6Until = 0; nativeBlock(); }
        requestCheck();
    }
    private void requestCheck() {
        if (pending.compareAndSet(false, true)) executor.execute(this::verify);
    }
    @android.annotation.SuppressLint("MissingPermission")
    private long networkHandle() {
        Network network = connectivity.getActiveNetwork();
        return network == null ? 0 : network.getNetworkHandle();
    }
    private void verify() {
        long epoch;
        synchronized (lock) { epoch = generation; }
        try {
            if (allowlist.isEmpty()) { report("blocked: empty allowlist"); return; }
            long network = networkHandle();
            synchronized (lock) {
                if (network != observedNetwork) { generation++; ipv4Until = ipv6Until = 0; nativeBlock(); observedNetwork = network; }
                epoch = generation;
            }
            if (network == 0) { report("blocked: no network"); return; }
            for (int family : new int[]{4, 6}) {
                boolean allowed = false;
                Observation observation = null;
                try {
                    PROBE.set(true); nativeProbe(true);
                    observation = probe(endpoint, family);
                    String ip = observation.ip();
                    if (IpAllowlist.address(ip).length != (family == 4 ? 4 : 16)) throw new IOException("Probe family mismatch");
                    allowed = allowlist.matches(ip);
                } catch (Exception error) { /* Timeout, TLS, framing or unknown IP means deny, never last-known allow. */ }
                finally { nativeProbe(false); PROBE.remove(); }
                synchronized (lock) {
                    if (generation != epoch || networkHandle() != network) {
                        generation++; ipv4Until = ipv6Until = 0; nativeBlock(); return;
                    }
                    // Recheck freshness at the native linearization point, after any Java/GC pause.
                    long until = nativePermit(family, allowed ? leaseMillis : 0,
                            observation == null ? 0 : observation.deadline());
                    if (family == 4) ipv4Until = until; else ipv6Until = until;
                }
            }
            report("IPv4=" + (ipv4Until > SystemClock.elapsedRealtime() ? "allowed" : "blocked")
                    + " IPv6=" + (ipv6Until > SystemClock.elapsedRealtime() ? "allowed" : "blocked"));
        } catch (Throwable error) {
            synchronized (lock) { ipv4Until = ipv6Until = 0; nativeBlock(); }
            report("blocked: verification unavailable (" + error.getClass().getSimpleName() + ")");
        } finally {
            boolean retry;
            synchronized (lock) { retry = generation != epoch; }
            pending.set(false);
            if (retry) requestCheck();
        }
    }

    private void report(String state) {
        if (!state.equals(lastStatus)) { lastStatus = state; XposedBridge.log("fkclaude: IP guard " + state); }
    }

    private record Observation(String ip, long deadline) {}

    private Observation probe(URI endpoint, int family) throws IOException {
        for (String key : new String[]{"http.proxyHost", "https.proxyHost", "socksProxyHost", "proxyHost"}) {
            String host = System.getProperty(key);
            if (host != null && !host.isBlank()) throw new IOException("Explicit client proxy requires separate route verification");
        }
        java.net.ProxySelector selector = java.net.ProxySelector.getDefault();
        ProbeResponse.requireDirect(selector == null ? java.util.List.of(java.net.Proxy.NO_PROXY) : selector.select(endpoint));
        try (Socket plain = new Socket(java.net.Proxy.NO_PROXY)) {
            AtomicBoolean expired = new AtomicBoolean();
            long validBefore = SystemClock.elapsedRealtime() + 5000;
            java.util.concurrent.ScheduledFuture<?> deadline = timeouts.schedule(() -> {
                expired.set(true);
                try { plain.close(); } catch (IOException ignored) {}
            }, 5, TimeUnit.SECONDS);
            try {
                String ip = exchange(plain, endpoint, family, expired);
                if (expired.get() || SystemClock.elapsedRealtime() >= validBefore) throw new java.net.SocketTimeoutException();
                return new Observation(ip, validBefore);
            }
            finally { deadline.cancel(false); }
        }
    }

    private String exchange(Socket plain, URI endpoint, int family, AtomicBoolean expired) throws IOException {
        InetAddress chosen = null;
        for (InetAddress address : InetAddress.getAllByName(endpoint.getHost()))
            if (address.getAddress().length == (family == 4 ? 4 : 16)) { chosen = address; break; }
        if (chosen == null) throw new IOException("Probe address family unavailable");
        if (expired.get()) throw new java.net.SocketTimeoutException();
        plain.connect(new InetSocketAddress(chosen, 443), 3000);
        plain.setSoTimeout(3000);
        try (SSLSocket tls = (SSLSocket) tlsFactory().createSocket(plain, endpoint.getHost(), 443, true)) {
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.setSoTimeout(3000);
            tls.startHandshake();
            String path = endpoint.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (endpoint.getRawQuery() != null) path += "?" + endpoint.getRawQuery();
            // No CookieHandler, account headers, redirects or session tokens are involved.
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + endpoint.getHost()
                    + "\r\nAccept: text/plain\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
            tls.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            tls.getOutputStream().flush();
            return ProbeResponse.read(tls.getInputStream());
        }
    }

    private SSLSocketFactory tlsFactory() throws IOException {
        if (probeTls == null) try {
            // Isolate probe session tickets and client key managers from the application's TLS context.
            javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(new javax.net.ssl.KeyManager[0], null, null);
            probeTls = context.getSocketFactory();
        } catch (java.security.GeneralSecurityException error) { throw new IOException("Probe TLS unavailable", error); }
        return probeTls;
    }

    private static native boolean nativeStart();
    private static native void nativeBlock();
    private static native long nativePermit(int family, int ttlMillis, long observationDeadline);
    private static native void nativeProbe(boolean enter);
}
