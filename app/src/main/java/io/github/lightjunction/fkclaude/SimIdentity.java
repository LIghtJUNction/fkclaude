package io.github.lightjunction.fkclaude;

import java.util.HashSet;
import java.util.Set;

/** Process-local synthetic SIM values; never reads a device or subscription service. */
final class SimIdentity {
    static String phone(Profile profile) {
        if (profile.has("sim_phone_number")) return profile.get("sim_phone_number");
        return profile.flag("block_personal_data") ? "" : null;
    }

    static String operatorPart(Profile profile, boolean country) {
        String operator = profile.get("sim_operator", "");
        if (!operator.matches("[0-9]{5,6}")) return "";
        return country ? operator.substring(0, 3) : operator.substring(3);
    }

    static Set<String> requiredHooks(int platformSdk) {
        Set<String> required = new HashSet<>();
        for (String method : new String[]{"getLine1Number", "getSimOperator", "getNetworkOperator",
                "getSimOperatorName", "getNetworkOperatorName", "getSubscriberId", "getSimSerialNumber",
                "getDeviceId", "getImei", "getMeid"})
            required.add("android.telephony.TelephonyManager#" + method);
        required.add("android.telecom.TelecomManager#getLine1Number");
        required.add("android.telecom.TelecomManager#getPhoneAccount");
        for (String method : new String[]{"getAddress", "getSubscriptionAddress", "writeToParcel", "toString"})
            required.add("android.telecom.PhoneAccount#" + method);
        for (String method : new String[]{"getNumber", "getCountryIso", "getMccString", "getMncString",
                "getMcc", "getMnc", "getCarrierName", "getDisplayName", "getIccId", "writeToParcel", "toString"})
            required.add("android.telephony.SubscriptionInfo#" + method);
        if (platformSdk >= 33) required.add("android.telephony.SubscriptionManager#getPhoneNumber");
        return Set.copyOf(required);
    }
}
