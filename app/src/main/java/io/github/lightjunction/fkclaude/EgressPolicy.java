package io.github.lightjunction.fkclaude;

/** Pure decision logic. A country result never replaces the explicitly configured allowlist. */
final class EgressPolicy {
    enum Decision {
        ALLOWED, EMPTY_ALLOWLIST, INVALID_RESPONSE, FAMILY_MISMATCH,
        COUNTRY_UNVERIFIED, ADDRESS_NOT_ALLOWED, EXPIRED_OBSERVATION, PROBE_FAILED, NATIVE_REJECTED;

        boolean allowed() { return this == ALLOWED; }
        String code() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    private EgressPolicy() {}

    static Decision evaluate(IpAllowlist allowlist, ProbeResponse.Reply reply, int family,
                             boolean usOnly, long nowMillis, long observationDeadline) {
        if (allowlist == null || allowlist.isEmpty()) return Decision.EMPTY_ALLOWLIST;
        if (reply == null) return Decision.INVALID_RESPONSE;
        if (family != 4 && family != 6) return Decision.FAMILY_MISMATCH;
        if (nowMillis < 0 || observationDeadline <= nowMillis) return Decision.EXPIRED_OBSERVATION;
        final byte[] address;
        try { address = IpAllowlist.address(reply.ip()); }
        catch (IllegalArgumentException ignored) { return Decision.INVALID_RESPONSE; }
        if (address.length != (family == 4 ? 4 : 16)) return Decision.FAMILY_MISMATCH;
        if (usOnly && !"US".equals(reply.country())) return Decision.COUNTRY_UNVERIFIED;
        return allowlist.matches(reply.ip()) ? Decision.ALLOWED : Decision.ADDRESS_NOT_ALLOWED;
    }
}
