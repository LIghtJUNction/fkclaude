package io.github.lightjunction.fkclaude;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Map;

public class SimIdentityTest {
    @Test public void absentNumberPreservesOrdinaryMode() {
        assertNull(SimIdentity.phone(new Profile(Map.of())));
    }
    @Test public void personalDataBlockWithoutNumberReturnsExplicitlyEmpty() {
        assertEquals("", SimIdentity.phone(new Profile(Map.of("block_personal_data", "true"))));
    }
    @Test public void configuredNumberAndExplicitEmptyHaveDifferentMeaningFromAbsent() {
        assertEquals("+12025550142", SimIdentity.phone(new Profile(Map.of("sim_phone_number", "+12025550142"))));
        assertEquals("", SimIdentity.phone(new Profile(Map.of("sim_phone_number", ""))));
    }
    @Test public void strictModeUsesOnlyTheReservedSyntheticNumber() {
        Profile p = new Profile(Map.of("privacy_us_only", "true", "sim_phone_number", "+12025550143",
                "sim_operator", "99999"));
        assertEquals(UsPrivacyPolicy.SIM_PHONE_NUMBER, SimIdentity.phone(p));
        assertEquals("310", SimIdentity.operatorPart(p, true));
        assertEquals("260", SimIdentity.operatorPart(p, false));
    }
    @Test public void operatorPartsPreserveLeadingZerosAndMncWidth() {
        Profile p = new Profile(Map.of("sim_operator", "00101"));
        assertEquals("001", SimIdentity.operatorPart(p, true));
        assertEquals("01", SimIdentity.operatorPart(p, false));
    }
    @Test public void missingOrMalformedOperatorIsUnavailable() {
        for (String value : new String[]{"", "310", "31026x", "3102600", " 310260", "３１０２６０"}) {
            Profile p = new Profile(Map.of("sim_operator", value));
            assertEquals("", SimIdentity.operatorPart(p, true));
            assertEquals("", SimIdentity.operatorPart(p, false));
        }
    }
    @Test public void modernPhoneHookIsRequiredOnlyWhereThePlatformProvidesIt() {
        String modern = "android.telephony.SubscriptionManager#getPhoneNumber";
        assertFalse(SimIdentity.requiredHooks(32).contains(modern));
        assertTrue(SimIdentity.requiredHooks(33).contains(modern));
        assertTrue(SimIdentity.requiredHooks(36).contains(modern));
        assertTrue(SimIdentity.requiredHooks(33).contains("android.telephony.TelephonyManager#getLine1Number"));
        assertTrue(SimIdentity.requiredHooks(33).contains("android.telephony.SubscriptionInfo#writeToParcel"));
        for (String method : new String[]{"getAddress", "getSubscriptionAddress", "writeToParcel", "toString"})
            assertTrue(SimIdentity.requiredHooks(32).contains("android.telecom.PhoneAccount#" + method));
        assertTrue(SimIdentity.requiredHooks(32).contains("android.telecom.TelecomManager#getPhoneAccount"));
    }
}
