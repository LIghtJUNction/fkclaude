package io.github.lightjunction.fkclaude;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** Immutable, DNS-free IP network allowlist. */
public final class IpAllowlist {
    private final List<Rule> rules;

    public IpAllowlist(String rules) {
        if (rules == null) throw new IllegalArgumentException("Null rules");
        List<Rule> parsed = new ArrayList<>();
        for (String line : rules.split("\\r?\\n")) {
            if (line.trim().startsWith("#")) continue;
            for (String token : line.split("[\\s,;]+")) {
                if (token.isEmpty()) continue;
                if (parsed.size() == 128) throw new IllegalArgumentException("Too many rules");
                String[] parts = token.split("/", -1);
                if (parts.length > 2) throw new IllegalArgumentException("Invalid CIDR");
                byte[] bytes = address(parts[0]);
                int bits = bytes.length * 8;
                if (parts.length == 2) {
                    if (!parts[1].matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid prefix");
                    bits = Integer.parseInt(parts[1]);
                    if (parts[0].contains(":") && bytes.length == 4) {
                        if (bits < 96 || bits > 128) throw new IllegalArgumentException("Invalid mapped prefix");
                        bits -= 96;
                    }
                    if (bits > bytes.length * 8) throw new IllegalArgumentException("Invalid prefix");
                }
                parsed.add(new Rule(bytes, bits));
            }
        }
        this.rules = List.copyOf(parsed);
    }

    public boolean isEmpty() { return rules.isEmpty(); }

    public boolean matches(String literal) {
        final byte[] candidate;
        try { candidate = address(literal); }
        catch (IllegalArgumentException e) { return false; }
        for (Rule rule : rules) {
            if (candidate.length != rule.bytes.length) continue;
            int full = rule.bits / 8;
            boolean match = true;
            for (int i = 0; i < full; i++) {
                if (candidate[i] != rule.bytes[i]) { match = false; break; }
            }
            int rest = rule.bits % 8;
            if (match && (rest == 0 || ((candidate[full] ^ rule.bytes[full]) & (0xff << (8 - rest))) == 0)) return true;
        }
        return false;
    }

    public static byte[] address(String literal) {
        if (literal == null || literal.isEmpty()) throw new IllegalArgumentException("Empty IP");
        if (!literal.contains(":")) return ipv4(literal);
        // Validate every character before InetAddress: names, scopes and brackets never reach it.
        if (!literal.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("Invalid IPv6 literal");
        if (literal.contains(".")) {
            int colon = literal.lastIndexOf(':');
            ipv4(literal.substring(colon + 1));
        }
        try {
            byte[] result = InetAddress.getByName(literal).getAddress();
            if (result.length == 16) {
                boolean mapped = true;
                for (int i = 0; i < 10; i++) mapped &= result[i] == 0;
                mapped &= result[10] == (byte) 255 && result[11] == (byte) 255;
                if (mapped) return java.util.Arrays.copyOfRange(result, 12, 16);
            }
            return result;
        } catch (UnknownHostException e) { throw new IllegalArgumentException("Invalid IPv6 literal", e); }
    }

    private static byte[] ipv4(String literal) {
        String[] octets = literal.split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("Invalid IPv4 literal");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String octet = octets[i];
            if (!octet.matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("Invalid octet");
            int n = Integer.parseInt(octet);
            if (n > 255) throw new IllegalArgumentException("Invalid octet");
            bytes[i] = (byte) n;
        }
        return bytes;
    }

    private static final class Rule {
        final byte[] bytes;
        final int bits;
        Rule(byte[] bytes, int bits) { this.bytes = bytes; this.bits = bits; }
    }
}
