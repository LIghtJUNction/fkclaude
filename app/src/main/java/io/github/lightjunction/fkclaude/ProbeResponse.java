package io.github.lightjunction.fkclaude;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded HTTP/1 probe parser. The caller owns the stream and its read timeout. */
public final class ProbeResponse {
    private static final int MAX_HEADERS = 8192;
    private static final int MAX_BODY = 4096;
    private ProbeResponse() {}

    /** A raw TLS probe cannot certify the exit of a separately configured HTTP/SOCKS client proxy. */
    public static void requireDirect(java.util.List<java.net.Proxy> route) throws IOException {
        if (route == null || route.isEmpty()) throw new IOException("Unknown proxy route");
        for (java.net.Proxy proxy : route)
            if (proxy == null || proxy.type() != java.net.Proxy.Type.DIRECT) throw new IOException("Unsupported explicit proxy route");
    }

    public static URI endpoint(String url) {
        if (url == null) throw new IllegalArgumentException("Null URL");
        for (int i = 0; i < url.length(); i++) {
            if (Character.isISOControl(url.charAt(i)) || url.charAt(i) > 127) throw new IllegalArgumentException("Non-ASCII or control in URL");
        }
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isEmpty()
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException("Unsafe endpoint");
            if (!host.matches("[A-Za-z0-9.-]+") || host.length() > 253) throw new IllegalArgumentException("Invalid hostname");
            String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
            for (String label : name.split("\\.", -1)) {
                if (!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) throw new IllegalArgumentException("Invalid hostname");
            }
            if (Pattern.compile("%[01][0-9a-f]|%7f", Pattern.CASE_INSENSITIVE).matcher(url).find()) throw new IllegalArgumentException("Encoded control");
            return uri;
        } catch (URISyntaxException e) { throw new IllegalArgumentException("Invalid URL", e); }
    }

    public static String read(InputStream input) throws IOException {
        int[] budget = {MAX_HEADERS};
        String status = line(input, budget);
        if (!status.matches("HTTP/1\\.[01] 200 [\\x20-\\x7e]*")) throw new IOException("Unexpected HTTP status");
        Map<String, String> headers = headers(input, budget);
        String length = headers.get("content-length");
        String transfer = headers.get("transfer-encoding");
        if (headers.containsKey("content-encoding") && !"identity".equalsIgnoreCase(headers.get("content-encoding"))) throw new IOException("Encoded body");
        if (length != null && transfer != null) throw new IOException("Conflicting framing");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (transfer != null) {
            if (!"chunked".equalsIgnoreCase(transfer)) throw new IOException("Unsupported transfer encoding");
            while (true) {
                String size = line(input, budget);
                if (!size.matches("[0-9a-fA-F]{1,8}")) throw new IOException("Invalid chunk size");
                long count = Long.parseLong(size, 16);
                if (count > MAX_BODY - body.size()) throw new IOException("Body too large");
                if (count == 0) {
                    Map<String, String> trailers = headers(input, budget);
                    if (trailers.containsKey("content-length") || trailers.containsKey("transfer-encoding") || trailers.containsKey("content-encoding")) throw new IOException("Framing trailer");
                    break;
                }
                copy(input, body, (int) count);
                if (!line(input, budget).isEmpty()) throw new IOException("Invalid chunk terminator");
            }
        } else if (length != null) {
            if (!length.matches("[0-9]+")) throw new IOException("Invalid content length");
            final long count;
            try { count = Long.parseLong(length); }
            catch (NumberFormatException e) { throw new IOException("Invalid content length", e); }
            if (count > MAX_BODY) throw new IOException("Body too large");
            copy(input, body, (int) count);
        } else {
            int b;
            while ((b = input.read()) != -1) {
                if (body.size() == MAX_BODY) throw new IOException("Body too large");
                body.write(b);
            }
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body.toByteArray())).toString();
        } catch (CharacterCodingException e) { throw new IOException("Invalid body encoding", e); }
        return extract(text);
    }

    private static Map<String, String> headers(InputStream input, int[] budget) throws IOException {
        Map<String, String> result = new HashMap<>();
        String line;
        while (!(line = line(input, budget)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon < 1 || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw new IOException("Invalid header");
            String key = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (result.put(key, line.substring(colon + 1).trim()) != null) throw new IOException("Duplicate header");
        }
        return result;
    }

    private static String line(InputStream input, int[] budget) throws IOException {
        StringBuilder result = new StringBuilder();
        while (true) {
            int b = input.read();
            if (b == -1) throw new IOException("Truncated HTTP line");
            if (--budget[0] < 0) throw new IOException("Headers too large");
            if (b == '\r') {
                int next = input.read();
                if (--budget[0] < 0 || next != '\n') throw new IOException("Invalid CRLF");
                return result.toString();
            }
            if (b < 32 || b > 126) throw new IOException("Non-ASCII HTTP line");
            result.append((char) b);
        }
    }

    private static void copy(InputStream input, ByteArrayOutputStream output, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            int b = input.read();
            if (b == -1) throw new IOException("Truncated body");
            output.write(b);
        }
    }

    private static String extract(String body) throws IOException {
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if ((c < 32 && c != '\r' && c != '\n' && c != '\t') || c == 127) throw new IOException("Control in body");
        }
        String trimmed = body.trim();
        try { IpAllowlist.address(trimmed); return trimmed; }
        catch (IllegalArgumentException ignored) { /* Try the two structured formats. */ }
        Matcher json = Pattern.compile("\\{\\s*\"ip\"\\s*:\\s*\"([0-9a-fA-F:.]+)\"\\s*}").matcher(trimmed);
        String ip = null;
        if (json.matches()) ip = json.group(1);
        else {
            for (String line : body.split("\\r?\\n", -1)) {
                if (line.isEmpty()) continue;
                // A trace consists solely of key=value lines; HTML and partial matches fail.
                if (!line.matches("[A-Za-z0-9_]+=[\\x20-\\x7e]*")) throw new IOException("Invalid trace");
                if (line.startsWith("ip=")) {
                    if (ip != null) throw new IOException("Duplicate IP");
                    ip = line.substring(3);
                }
            }
        }
        try { IpAllowlist.address(ip); return ip; }
        catch (IllegalArgumentException e) { throw new IOException("Missing or invalid IP", e); }
    }
}
