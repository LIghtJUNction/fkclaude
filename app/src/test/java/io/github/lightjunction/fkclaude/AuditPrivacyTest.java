package io.github.lightjunction.fkclaude;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AuditPrivacyTest {
    @Test public void credentialsAccountIdsAndQueryValuesNeverSurviveUrls() {
        String raw = "https://user:password@api.claude.ai/api/organizations/55f22f65-9d8f-441c-94e3-75305bb01cd2/"
                + "chat_conversations/customer@example.org?token=secret&q=private%20prompt#another-secret";
        String safe = AuditSanitizer.url(raw);
        assertEquals("https://api.claude.ai/api/organizations/:id/chat_conversations/:id?token=[redacted]&q=[redacted]", safe);
        for (String secret : new String[]{"user", "password", "55f22f65", "customer", "secret", "private", "another"})
            assertFalse(secret, safe.contains(secret));
    }

    @Test public void unknownAndEncodedPathSegmentsRemainOpaque() {
        String path = AuditSanitizer.path("/api/secret-here/%73ecret/%2Fvar%2Fprivate/unknown?key=value");
        assertEquals("/api/:segment/:segment/:segment/:segment", path);
        assertEquals("/api/accounts/:id/mobile_events", AuditSanitizer.path("/api/accounts/analytics/mobile_events"));
        assertEquals("/v1/code/sessions/:id/events", AuditSanitizer.path("/v1/code/sessions/user-session/events"));
    }

    @Test public void malformedAndNonNetworkUrlsDoNotEchoInput() {
        assertEquals("[invalid-url]", AuditSanitizer.url("https://host/secret value"));
        assertEquals("[non-http-url]", AuditSanitizer.url("content://contacts/private"));
        assertEquals("[non-http-url]", AuditSanitizer.url("file:///data/secret"));
        assertEquals("[invalid-url]", AuditSanitizer.url("https://" + "x".repeat(9000)));
        assertFalse(AuditSanitizer.url("https://example.com/?private-name=secret").contains("private-name"));
        assertEquals("https://[ip]/:segment", AuditSanitizer.url("https://192.168.10.22/private"));
    }

    @Test public void detailsRejectPayloadsHeadersAndUnrecognisedFields() {
        Map<String, String> safe = AuditSanitizer.details(Map.of(
                "body", "private conversation", "Authorization", "Bearer token",
                "cookies", "session=private", "device_id", "actual identifier",
                "method", "POST", "fields", "android_id,device_model",
                "path", "/v3/accounts/user-account/mobile_events", "request_id", "r42",
                "status_code", "204"));
        assertEquals(5, safe.size());
        assertEquals("android_id,device_model", safe.get("fields"));
        assertEquals("/v3/accounts/:id/mobile_events", safe.get("path"));
        assertFalse(safe.toString().contains("private"));
        assertThrows(UnsupportedOperationException.class, () -> safe.put("body", "x"));
    }

    @Test public void requestCorrelationIsLocalAndMediaTypeDropsBoundary() {
        Map<String, String> safe = AuditSanitizer.details(Map.of(
                "request_id", "55f22f65-9d8f-441c-94e3-75305bb01cd2",
                "content_type", "multipart/form-data; boundary=private-boundary",
                "port", "443", "bytes", "123", "error_type", "java.io.IOException", "api", "android.provider.Settings$Secure#getString"));
        assertEquals("[id]", safe.get("request_id"));
        assertEquals("multipart/form-data", safe.get("content_type"));
        assertEquals("443", safe.get("port"));
        assertEquals("java.io.IOException", safe.get("error_type"));
        assertEquals("android.provider.Settings$Secure#getString", safe.get("api"));
    }

    @Test public void freeMetadataRedactsCredentialsIdentifiersAndInjection() {
        String safe = AuditSanitizer.text("Bearer eyJprivate.token "
                + "customer@example.org 55f22f65-9d8f-441c-94e3-75305bb01cd2 "
                + "deadbeefdeadbeef\ninjected\rline\t123456789");
        for (String secret : new String[]{"eyJprivate", "customer@", "55f22f65", "deadbeef", "123456789", "\n", "\r", "\t"})
            assertFalse(secret, safe.contains(secret));
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        for (int i = 0; i < 10000; i++) details.put("unknown_" + i, "secret".repeat(1000));
        details.put("method", "POST");
        assertEquals(Map.of("method", "POST"), AuditSanitizer.details(details));
        assertTrue(AuditSanitizer.text("word ".repeat(5000)).length() <= 241);
    }

    @Test public void telemetryClassificationRequiresCorrectDestinationOrKnownRoute() {
        assertEquals("telemetry", AuditClassifier.category("POST", "api3.siftscience.com", "/v3/accounts/account/mobile_events"));
        assertEquals("telemetry", AuditClassifier.category("POST", "o1158394.ingest.us.sentry.io", "/api/42/envelope"));
        assertEquals("telemetry", AuditClassifier.category("POST", "browser-intake-ap1-datadoghq.com", "/api/v2/rum"));
        assertEquals("telemetry", AuditClassifier.category("POST", "claude.ai", "/api/event_logging/v2/batch"));
        assertEquals("network", AuditClassifier.category("POST", "sentry.io.attacker.example", "/api/envelope"));
        assertEquals("network", AuditClassifier.category("POST", "claude.ai", "/api/unknown/events"));
        assertEquals("network", AuditClassifier.category("GET", "firebaseinstallations.googleapis.com", "/v1/projects/project"));
    }

    @Test public void userSessionEventsAreNotClassifiedAsTelemetry() {
        assertEquals("conversation", AuditClassifier.category("POST", "claude.ai", "/v1/code/sessions/abc/events"));
        assertEquals("conversation", AuditClassifier.category("POST", "api.anthropic.com", "/v1/messages"));
        assertEquals("conversation", AuditClassifier.category("GET", "claude.ai", "/api/organizations/org/chat_conversations"));
        assertEquals("network", AuditClassifier.category("POST", "other.example", "/v1/messages"));
        assertEquals("telemetry", AuditClassifier.category("POST", "claude.ai", "/api/organizations/org/notification/push/track_open"));
        assertEquals("telemetry", AuditClassifier.category("POST", "claude.ai", "/api/organizations/org/experiences/track"));
        assertEquals("integrity", AuditClassifier.category("POST", "claude.ai", "/api/auth/session_reattest/device_key"));
    }

    @Test public void nonConversationFilterIncludesUnknownNetworkButExcludesDeviceReads() {
        assertTrue(AuditLog.matches("{\"source\":\"OkHttp\"}", "network", "okhttp", "", true));
        assertTrue(AuditLog.matches("entry", "telemetry", "", "all", true));
        assertFalse(AuditLog.matches("entry", "conversation", "", "", true));
        assertFalse(AuditLog.matches("entry", "device", "", "", true));
        assertTrue(AuditLog.matches("entry", "device", "", "", false));
        assertFalse(AuditLog.matches("entry", "telemetry", "", "network", false));
        assertFalse(AuditLog.matches("entry", "network", "missing", "", false));
    }

    @Test public void jsonEscapingPreventsLineAndStructureInjection() {
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\u0000\"", AuditLog.quote("a\"b\\c\n\r\t\0"));
        assertEquals("\"\\ud800\"", AuditLog.quote("\ud800"));
        assertFalse(AuditLog.quote("one\nfake-event").contains("\n"));
    }
}
