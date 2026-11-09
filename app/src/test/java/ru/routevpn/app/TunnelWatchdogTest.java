package ru.routevpn.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TunnelWatchdogTest {
    @Test public void startupGraceAndSleepNeverTriggerProbes() {
        TunnelWatchdog value = new TunnelWatchdog(); value.started(0);
        assertFalse(value.shouldProbe(14_999, true)); assertTrue(value.shouldProbe(15_000, true));
        assertFalse(value.shouldProbe(900_000, false)); assertEquals(0, value.totalRestarts());
    }
    @Test public void staleHandshakeAndIdleTrafficAreNotFailures() {
        TunnelWatchdog value = new TunnelWatchdog(); value.started(0); value.observe(1_000, 100, 100);
        assertFalse(value.shouldProbe(30_999, true)); assertTrue(value.shouldProbe(31_000, true));
        assertEquals(0, value.failedProbes()); assertEquals(0, value.totalRestarts());
    }
    @Test public void threeConsecutiveFailuresAreRequiredAndRetriedWithSpacing() {
        TunnelWatchdog value = new TunnelWatchdog(); value.started(0);
        assertFalse(value.complete(TunnelWatchdog.Result.FAILURE, 15_000, value.beginProbe(15_000)));
        assertFalse(value.shouldProbe(24_999, true)); assertTrue(value.shouldProbe(25_000, true));
        assertFalse(value.complete(TunnelWatchdog.Result.FAILURE, 25_000, value.beginProbe(25_000)));
        assertTrue(value.complete(TunnelWatchdog.Result.FAILURE, 35_000, value.beginProbe(35_000)));
    }
    @Test public void trafficArrivingDuringProbeInvalidatesItsTimeout() {
        TunnelWatchdog value = new TunnelWatchdog(); value.started(0);
        value.complete(TunnelWatchdog.Result.FAILURE, 15_000, value.beginProbe(15_000));
        value.complete(TunnelWatchdog.Result.FAILURE, 25_000, value.beginProbe(25_000));
        long sequence = value.beginProbe(35_000); value.observe(36_000, 50, 0);
        assertFalse(value.complete(TunnelWatchdog.Result.FAILURE, 37_000, sequence));
        assertEquals(0, value.failedProbes());
    }
    @Test public void successfulProbeAndUnavailableBindingClearFailures() {
        TunnelWatchdog value = new TunnelWatchdog(); value.started(0);
        value.complete(TunnelWatchdog.Result.FAILURE, 15_000, value.beginProbe(15_000));
        assertFalse(value.complete(TunnelWatchdog.Result.UNAVAILABLE, 25_000, value.beginProbe(25_000)));
        assertEquals(0, value.failedProbes());
        value.complete(TunnelWatchdog.Result.FAILURE, 35_000, value.beginProbe(35_000));
        assertFalse(value.complete(TunnelWatchdog.Result.SUCCESS, 45_000, value.beginProbe(45_000)));
        assertEquals(0, value.failedProbes()); assertFalse(value.shouldProbe(104_999, true));
        assertTrue(value.shouldProbe(105_000, true));
    }
    @Test public void pauseClearsAnIncompleteFailureSeries() {
        TunnelWatchdog value = new TunnelWatchdog(); value.started(0);
        value.complete(TunnelWatchdog.Result.FAILURE, 15_000, value.beginProbe(15_000));
        value.paused(); assertEquals(0, value.failedProbes());
    }
    @Test public void restartBudgetSurvivesRecreationAndExpiresAfterFifteenMinutes() {
        TunnelWatchdog value = new TunnelWatchdog(); value.started(0);
        for (long now : new long[] {30_000, 100_000, 170_000}) {
            for (int i = 0; i < 2; i++) assertFalse(value.complete(TunnelWatchdog.Result.FAILURE, now - 20_000 + i * 10_000, value.beginProbe(now - 20_000 + i * 10_000)));
            assertTrue(value.complete(TunnelWatchdog.Result.FAILURE, now, value.beginProbe(now)));
            value.restarting(now); value.started(now);
        }
        for (int i = 0; i < 3; i++) assertFalse(value.complete(TunnelWatchdog.Result.FAILURE, 200_000 + i * 10_000, value.beginProbe(200_000 + i * 10_000)));
        assertEquals(3, value.totalRestarts());
        assertTrue(value.complete(TunnelWatchdog.Result.FAILURE, 930_000, value.beginProbe(930_000)));
    }
    @Test public void cooldownPreventsRapidRepeatedRestarts() {
        TunnelWatchdog value = new TunnelWatchdog(); value.started(0);
        for (int i = 0; i < 3; i++) value.complete(TunnelWatchdog.Result.FAILURE, 10_000 + i * 10_000, value.beginProbe(10_000 + i * 10_000));
        value.restarting(30_000); value.started(30_000);
        for (int i = 0; i < 3; i++) assertFalse(value.complete(TunnelWatchdog.Result.FAILURE, 45_000 + i * 10_000, value.beginProbe(45_000 + i * 10_000)));
        assertTrue(value.complete(TunnelWatchdog.Result.FAILURE, 90_000, value.beginProbe(90_000)));
    }
}
