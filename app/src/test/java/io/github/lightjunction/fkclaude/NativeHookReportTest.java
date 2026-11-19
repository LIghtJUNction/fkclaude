package io.github.lightjunction.fkclaude;

import org.junit.Test;
import static org.junit.Assert.*;

public class NativeHookReportTest {
    @Test public void completeRequiredAndExtraProjectionsAreAccepted() {
        assertTrue(NativeHookReport.completeForUsPolicy(new long[]{1, 4, 4, 0, 1}));
        assertTrue(NativeHookReport.completeForUsPolicy(new long[]{1, 11, 11, 0, 1}));
    }
    @Test public void missingOrMalformedReportsAreRejected() {
        assertFalse(NativeHookReport.completeForUsPolicy(null));
        assertFalse(NativeHookReport.completeForUsPolicy(new long[]{}));
        assertFalse(NativeHookReport.completeForUsPolicy(new long[]{1, 4, 4, 0}));
        assertFalse(NativeHookReport.completeForUsPolicy(new long[]{1, 4, 4, 0, 1, 1}));
    }
    @Test public void unavailableOrUnacceptedConfigurationCannotPass() {
        assertFalse(NativeHookReport.completeForUsPolicy(new long[]{0, 4, 4, 0, 1}));
        assertFalse(NativeHookReport.completeForUsPolicy(new long[]{1, 4, 4, 0, 0}));
        assertFalse(NativeHookReport.completeForUsPolicy(new long[]{2, 4, 4, 0, 1}));
    }
    @Test public void PartialFailedOrTooSmallReportsCannotPass() {
        for (long[] report : new long[][]{{1, 4, 3, 1, 1}, {1, 4, 3, 0, 1}, {1, 4, 4, 1, 1},
                {1, 3, 3, 0, 1}, {1, 0, 0, 0, 1}, {1, -4, -4, 0, 1}, {1, 4, 5, 0, 1}})
            assertFalse(NativeHookReport.completeForUsPolicy(report));
    }
}
