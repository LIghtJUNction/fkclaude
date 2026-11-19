package io.github.lightjunction.fkclaude;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class UsEgressPolicyTest {
    private static final String V4 = "203.0.113.7";
    private static final String V6 = "2001:db8::7";
    private static final IpAllowlist LIST = new IpAllowlist("203.0.113.0/24;2001:db8::/32");

    private static ProbeResponse.Reply strict(String body) throws IOException {
        return parse("HTTP/1.1 200 OK\r\n\r\n" + body, true);
    }
    private static ProbeResponse.Reply parse(String http, boolean strict) throws IOException {
        return ProbeResponse.readReply(new ByteArrayInputStream(http.getBytes(StandardCharsets.UTF_8)), strict);
    }
    private static EgressPolicy.Decision decision(ProbeResponse.Reply reply, int family) {
        return EgressPolicy.evaluate(LIST, reply, family, true, 100, 5100);
    }
    private static void rejected(String body) {
        assertThrows(body, IOException.class, () -> strict(body));
    }

    @Test public void visitorCountryIsIndependentOfEdgeDatacenter() throws IOException {
        assertEquals(EgressPolicy.Decision.ALLOWED, decision(strict("ip=" + V4 + "\ncolo=NRT\nloc=US\n"), 4));
        assertEquals(EgressPolicy.Decision.COUNTRY_UNVERIFIED, decision(strict("ip=" + V4 + "\ncolo=SJC\nloc=JP\n"), 4));
        rejected("ip=" + V4 + "\ncolo=SJC\ncountry=US\n");
    }

    @Test public void strictModeRequiresTraceCountry() {
        for (String body : new String[]{V4, "{\"ip\":\"" + V4 + "\"}", "ip=" + V4,
                "ip=" + V4 + "\nloc=\n", "ip=" + V4 + "\nloc=us\n",
                "ip=" + V4 + "\nloc=USA\n", "ip=" + V4 + "\nloc=US \n",
                "ip=" + V4 + "\nloc=U1\n", "ip=" + V4 + "\nloc=US\t\n"}) rejected(body);
    }

    @Test public void duplicateAndCaseAmbiguousFieldsAreRejected() {
        for (String suffix : new String[]{"loc=US\nloc=US", "loc=US\nloc=JP",
                "loc=US\nLOC=US", "Loc=US", "LOC=US", "loc=US\nIP=" + V4,
                "loc=US\nip=" + V4}) rejected("ip=" + V4 + "\n" + suffix + "\n");
    }

    @Test public void onlyExactUsCountryIsEligible() throws IOException {
        for (String country : new String[]{null, "", "JP", "CA", "XX", "T1", "us", "US ", "US\u0000"})
            assertEquals(EgressPolicy.Decision.COUNTRY_UNVERIFIED, decision(new ProbeResponse.Reply(V4, country), 4));
        assertEquals(EgressPolicy.Decision.COUNTRY_UNVERIFIED, decision(strict("ip=" + V4 + "\nloc=XX\n"), 4));
        assertEquals(EgressPolicy.Decision.ALLOWED, decision(strict("ip=" + V4 + "\nloc=US\n"), 4));
    }

    @Test public void countryDoesNotReplaceExplicitAllowlist() {
        ProbeResponse.Reply us = new ProbeResponse.Reply(V4, "US");
        assertEquals(EgressPolicy.Decision.EMPTY_ALLOWLIST,
                EgressPolicy.evaluate(new IpAllowlist(""), us, 4, true, 100, 5100));
        assertEquals(EgressPolicy.Decision.EMPTY_ALLOWLIST,
                EgressPolicy.evaluate(null, us, 4, true, 100, 5100));
        assertEquals(EgressPolicy.Decision.ADDRESS_NOT_ALLOWED,
                EgressPolicy.evaluate(new IpAllowlist("192.0.2.0/24"), us, 4, true, 100, 5100));
    }

    @Test public void bothFamiliesNeedTheirOwnMatchingObservation() throws IOException {
        ProbeResponse.Reply v4 = strict("ip=" + V4 + "\nloc=US\n");
        ProbeResponse.Reply v6 = strict("ip=" + V6 + "\nloc=US\n");
        assertEquals(EgressPolicy.Decision.ALLOWED, decision(v4, 4));
        assertEquals(EgressPolicy.Decision.ALLOWED, decision(v6, 6));
        assertEquals(EgressPolicy.Decision.FAMILY_MISMATCH, decision(v4, 6));
        assertEquals(EgressPolicy.Decision.FAMILY_MISMATCH, decision(v6, 4));
        ProbeResponse.Reply mapped = strict("ip=::ffff:" + V4 + "\nloc=US\n");
        assertEquals(EgressPolicy.Decision.ALLOWED, decision(mapped, 4));
        assertEquals(EgressPolicy.Decision.FAMILY_MISMATCH, decision(mapped, 6));
    }

    @Test public void staleOrInvalidObservationCannotRenewPermission() {
        ProbeResponse.Reply us = new ProbeResponse.Reply(V4, "US");
        for (long deadline : new long[]{0, 99, 100})
            assertEquals(EgressPolicy.Decision.EXPIRED_OBSERVATION,
                    EgressPolicy.evaluate(LIST, us, 4, true, 100, deadline));
        assertEquals(EgressPolicy.Decision.EXPIRED_OBSERVATION,
                EgressPolicy.evaluate(LIST, us, 4, true, -1, 100));
        assertEquals(EgressPolicy.Decision.ALLOWED,
                EgressPolicy.evaluate(LIST, us, 4, true, 100, 101));
        assertEquals(EgressPolicy.Decision.INVALID_RESPONSE, decision(null, 4));
        assertEquals(EgressPolicy.Decision.INVALID_RESPONSE, decision(new ProbeResponse.Reply("localhost", "US"), 4));
        assertEquals(EgressPolicy.Decision.FAMILY_MISMATCH, decision(us, 0));
    }

    @Test public void ordinaryGuardPreservesIpOnlyCompatibility() throws IOException {
        for (String body : new String[]{V4, "{\"ip\":\"" + V4 + "\"}",
                "ip=" + V4 + "\nloc=anything\nloc=duplicate\n"}) {
            ProbeResponse.Reply reply = parse("HTTP/1.1 200 OK\r\n\r\n" + body, false);
            assertEquals(V4, reply.ip());
            assertNull(reply.country());
            assertEquals(EgressPolicy.Decision.ALLOWED,
                    EgressPolicy.evaluate(LIST, reply, 4, false, 100, 5100));
        }
    }

    @Test public void strictReplyStillRequiresCompleteSuccessfulHttp() {
        String body = "ip=" + V4 + "\nloc=US\n";
        for (String http : new String[]{
                "HTTP/1.1 302 Found\r\n\r\n" + body,
                "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n" + body,
                "HTTP/1.1 200 OK\r\nContent-Length: " + (body.length() + 1) + "\r\n\r\n" + body,
                "HTTP/1.1 200 OK\r\n\r\n<html>\n" + body + "</html>"})
            assertThrows(IOException.class, () -> parse(http, true));
    }
}
