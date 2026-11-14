package io.github.lightjunction.fkclaude;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class IpGuardParsingTest {
    @Test public void explicitProxyCannotBeCertifiedByADirectProbe() throws IOException {
        ProbeResponse.requireDirect(java.util.List.of(java.net.Proxy.NO_PROXY));
        for (java.net.Proxy.Type type : new java.net.Proxy.Type[]{java.net.Proxy.Type.HTTP, java.net.Proxy.Type.SOCKS}) {
            java.net.Proxy proxy = new java.net.Proxy(type, new java.net.InetSocketAddress("127.0.0.1", 1080));
            assertThrows(IOException.class, () -> ProbeResponse.requireDirect(java.util.List.of(proxy, java.net.Proxy.NO_PROXY)));
        }
        assertThrows(IOException.class, () -> ProbeResponse.requireDirect(java.util.List.of()));
        assertThrows(IOException.class, () -> ProbeResponse.requireDirect(null));
    }
    @Test public void identityEncodingIsCleartextAndEndpointsRequireAscii() throws IOException {
        assertEquals("203.0.113.7", read("HTTP/1.1 200 OK\r\nContent-Encoding: identity\r\n\r\n203.0.113.7"));
        assertThrows(IllegalArgumentException.class, () -> ProbeResponse.endpoint("https://example.com/中文"));
    }
    private static final String IP = "203.0.113.7";
    private static String read(String raw) throws IOException {
        return ProbeResponse.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }
    private static String response(String body) { return "HTTP/1.1 200 OK\r\n\r\n" + body; }
    private static void badResponse(String raw) {
        assertThrows(IOException.class, () -> read(raw));
    }

    @Test public void emptyAndCommentsDeny() {
        IpAllowlist list = new IpAllowlist(" \n # comment\n , ; \n");
        assertTrue(list.isEmpty());
        assertFalse(list.matches(IP));
        assertFalse(list.matches("::1"));
    }

    @Test public void ipv4BoundariesAndSeparators() {
        IpAllowlist list = new IpAllowlist("192.0.2.129/25, 203.0.113.7;10.0.0.0/31\n127.0.0.1");
        assertFalse(list.isEmpty());
        assertFalse(list.matches("192.0.2.127"));
        assertTrue(list.matches("192.0.2.128"));
        assertTrue(list.matches("192.0.2.255"));
        assertFalse(list.matches("192.0.3.0"));
        assertTrue(list.matches("10.0.0.0"));
        assertTrue(list.matches("10.0.0.1"));
        assertFalse(list.matches("10.0.0.2"));
        assertTrue(list.matches(IP));
        assertFalse(list.matches("203.0.113.8"));
        assertFalse(list.matches("localhost"));
    }

    @Test public void ipv6AndFamilyBoundaries() {
        IpAllowlist list = new IpAllowlist("2001:db8::/33;::1/128");
        assertTrue(list.matches("2001:db8:7fff:ffff:ffff:ffff:ffff:ffff"));
        assertFalse(list.matches("2001:db8:8000::"));
        assertTrue(list.matches("0:0:0:0:0:0:0:1"));
        assertFalse(list.matches("::2"));
        assertFalse(list.matches(IP));
        assertTrue(new IpAllowlist("0.0.0.0/0").matches("255.255.255.255"));
        assertFalse(new IpAllowlist("0.0.0.0/0").matches("::1"));
        assertTrue(new IpAllowlist("::/0").matches("ffff::1"));
        assertFalse(new IpAllowlist("::/0").matches(IP));
    }

    @Test public void mappedAddressesNormalize() {
        assertArrayEquals(IpAllowlist.address(IP), IpAllowlist.address("::ffff:203.0.113.7"));
        assertArrayEquals(IpAllowlist.address(IP), IpAllowlist.address("0:0:0:0:0:ffff:cb00:7107"));
        IpAllowlist list = new IpAllowlist("::ffff:203.0.113.7/120");
        assertTrue(list.matches("203.0.113.255"));
        assertTrue(list.matches("::ffff:203.0.113.0"));
        assertFalse(list.matches("203.0.114.0"));
        assertTrue(new IpAllowlist(IP).matches("::ffff:203.0.113.7"));
        assertTrue(new IpAllowlist("::ffff:0:0/96").matches(IP));
        assertThrows(IllegalArgumentException.class, () -> new IpAllowlist("::ffff:0:0/95"));
    }

    @Test public void invalidLiteralsAndRules() {
        for (String value : new String[]{"", "localhost", "example.com", "127.1", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01.2.3.4", "+1.2.3.4", " 1.2.3.4", "1.2.3.4\n", "[::1]", "fe80::1%eth0", "1:::2", "abcd", "::ffff:01.2.3.4"}) {
            assertThrows(value, IllegalArgumentException.class, () -> IpAllowlist.address(value));
        }
        assertThrows(IllegalArgumentException.class, () -> IpAllowlist.address(null));
        for (String rule : new String[]{"1.2.3.4/33", "::/129", "::/-1", "::/", "::/1/2", "1.2.3.4 # inline"}) {
            assertThrows(rule, IllegalArgumentException.class, () -> new IpAllowlist(rule));
        }
        assertFalse(new IpAllowlist("1.2.3.4;::1").matches(null));
        assertFalse(new IpAllowlist("1.2.3.4 ".repeat(128)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new IpAllowlist("1.2.3.4 ".repeat(129)));
    }

    @Test public void endpointSafety() {
        assertEquals("example.com", ProbeResponse.endpoint("https://example.com").getHost());
        assertEquals(443, ProbeResponse.endpoint("https://example.com:443/cdn-cgi/trace?x=1").getPort());
        for (String url : new String[]{"http://example.com", "https://example.com:444/", "https://user@example.com/", "https://example.com/#x", "https:///x", "https://é.example/", "https://example.com/\r\nx", "https://example.com/%0d%0aX", "https://-bad.example/", "https://example..com/", "https://example.com/\u007f"}) {
            assertThrows(url, IllegalArgumentException.class, () -> ProbeResponse.endpoint(url));
        }
    }

    @Test public void validFramingAndFormats() throws Exception {
        assertEquals(IP, read(response(IP)));
        assertEquals(IP, read("HTTP/1.0 200 OK\r\nContent-Length: 11\r\n\r\n" + IP));
        assertEquals(IP, read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\n203.0\r\n6\r\n.113.7\r\n0\r\nX-Trace: done\r\n\r\n"));
        assertEquals(IP, read(response("fl=abc\nip=" + IP + "\ntls=TLSv1.3\n")));
        assertEquals("2001:db8::1", read(response(" { \"ip\" : \"2001:db8::1\" } \n")));
        assertEquals("::ffff:203.0.113.7", read(response("::ffff:203.0.113.7")));
    }

    @Test public void statusEncodingAndDuplicateFramingFail() {
        for (int status : new int[]{100, 201, 301, 302, 307, 401, 403, 500}) {
            badResponse("HTTP/1.1 " + status + " Nope\r\nLocation: https://example.com\r\n\r\n" + IP);
        }
        for (String headers : new String[]{"Content-Length: 11\r\nContent-Length: 11", "Content-Length: 11\r\ncontent-length: 12", "Content-Length: 11\r\nTransfer-Encoding: chunked", "Content-Encoding: gzip", "Content-Encoding: br", "Transfer-Encoding: gzip, chunked", "Content-Length: -1", "Content-Length: 11,11", "Content-Length: 999999999999999999999999", " Bad: folded", "No colon"}) {
            badResponse("HTTP/1.1 200 OK\r\n" + headers + "\r\n\r\n" + IP);
        }
        badResponse("HTTP/1.1 200\r\n\r\n" + IP);
        badResponse("HTTP/2 200 OK\r\n\r\n" + IP);
        badResponse("HTTP/1.1 2000 OK\r\n\r\n" + IP);
        badResponse("HTTP/1.1 200 OK\n\n" + IP);
    }

    @Test public void truncatedAndInvalidChunksFail() {
        badResponse("");
        badResponse("HTTP/1.1 200 OK\r\n");
        badResponse("HTTP/1.1 200 OK\r\nContent-Length: 12\r\n\r\n" + IP);
        for (String chunks : new String[]{"b\r\n203.", "b\r\n" + IP, "b\r\n" + IP + "xx\r\n0\r\n\r\n", "z\r\n", "-1\r\n", "0\r\n", "0\r\nContent-Length: 0\r\n\r\n", "1001\r\n"}) {
            badResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + chunks);
        }
    }

    @Test public void timeoutPropagatesDuringBodyRead() {
        byte[] prefix = "HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n203.".getBytes(StandardCharsets.US_ASCII);
        InputStream input = new InputStream() {
            int offset;
            @Override public int read() throws IOException {
                if (offset < prefix.length) return prefix[offset++];
                throw new SocketTimeoutException("test timeout");
            }
        };
        assertThrows(SocketTimeoutException.class, () -> ProbeResponse.read(input));
    }

    @Test public void boundedHeadersAndBodies() throws Exception {
        badResponse("HTTP/1.1 200 OK\r\nX: " + "a".repeat(8192) + "\r\n\r\n" + IP);
        badResponse("HTTP/1.1 200 OK\r\nContent-Length: 4097\r\n\r\n");
        badResponse(response(" ".repeat(4097)));
        assertEquals(IP, read(response(IP + " ".repeat(4096 - IP.length()))));
        badResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1000\r\n" + " ".repeat(4096) + "\r\n1\r\nx\r\n0\r\n\r\n");
    }

    @Test public void bodyMustContainOneUnambiguousLiteral() {
        for (String body : new String[]{"<html>ip=" + IP + "</html>", "prefix " + IP, "ip=" + IP + "\nip=" + IP, "ip=localhost", "ip= " + IP, "{\"ip\":\"" + IP + "\",\"ip\":\"" + IP + "\"}", "{\"ip\":\"" + IP + "\",\"other\":1}", "{\"ip\":\"" + IP + "\"}garbage", "{\"ip\":\"localhost\"}", "ip=", "<html>\nip=" + IP + "\n</html>", "{broken}", ""}) {
            badResponse(response(body));
        }
        badResponse(response("203.0.113.7\u0000"));
        badResponse(response("\u00ff"));
    }
    @Test public void exactHeaderLimitAndStrictUtf8() throws Exception {
        String base = "HTTP/1.1 200 OK\r\nX: ";
        String suffix = "\r\n\r\n";
        String exact = base + "a".repeat(8192 - base.length() - suffix.length()) + suffix;
        assertEquals(IP, read(exact + IP));
        badResponse(base + "a".repeat(8193 - base.length() - suffix.length()) + suffix + IP);
        byte[] head = response("").getBytes(StandardCharsets.US_ASCII);
        byte[] invalid = java.util.Arrays.copyOf(head, head.length + 2);
        invalid[head.length] = (byte) 0xc3;
        invalid[head.length + 1] = (byte) 0x28;
        assertThrows(IOException.class, () -> ProbeResponse.read(new ByteArrayInputStream(invalid)));
    }

    @Test public void timeoutAlsoPropagatesForHeadersChunksAndCloseDelimited() {
        for (String prefix : new String[]{"HTTP/1.1 200", response("203."),
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nb\r\n203."}) {
            byte[] bytes = prefix.getBytes(StandardCharsets.US_ASCII);
            InputStream input = new InputStream() {
                int offset;
                @Override public int read() throws IOException {
                    if (offset < bytes.length) return bytes[offset++];
                    throw new SocketTimeoutException("test timeout");
                }
            };
            assertThrows(SocketTimeoutException.class, () -> ProbeResponse.read(input));
        }
    }

}
