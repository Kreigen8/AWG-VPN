package ru.routevpn.app;

import java.util.ArrayDeque;

/** Monotonic recovery policy. Idle/stale handshakes alone never cause a restart. */
final class TunnelWatchdog {
    enum Result { SUCCESS, FAILURE, UNAVAILABLE }
    static final long STARTUP_GRACE_MS = 15_000, QUIET_MS = 30_000;
    static final long PROBE_INTERVAL_MS = 60_000, FAILED_PROBE_INTERVAL_MS = 10_000;
    static final long RESTART_COOLDOWN_MS = 60_000, BUDGET_WINDOW_MS = 15 * 60_000;
    static final int FAILURE_LIMIT = 3, RESTART_LIMIT = 3;
    private final ArrayDeque<Long> restarts = new ArrayDeque<>();
    private long started, lastProgress, lastProbe, rx, handshake, progressSequence;
    private boolean received;
    private volatile int failures, totalRestarts;

    void started(long now) {
        started = now; lastProgress = now; lastProbe = now - PROBE_INTERVAL_MS;
        rx = 0; handshake = 0; received = false; failures = 0; progressSequence++;
    }
    void observe(long now, long receivedBytes, long latestHandshake) {
        if (receivedBytes > rx || latestHandshake > handshake) {
            received = true; lastProgress = now; failures = 0; progressSequence++;
        }
        rx = receivedBytes; handshake = Math.max(handshake, latestHandshake);
    }
    boolean shouldProbe(long now, boolean interactive) {
        if (!interactive || now - started < STARTUP_GRACE_MS) return false;
        long quiet = received ? QUIET_MS : STARTUP_GRACE_MS;
        long interval = failures > 0 ? FAILED_PROBE_INTERVAL_MS : PROBE_INTERVAL_MS;
        return now - lastProgress >= quiet && now - lastProbe >= interval;
    }
    long beginProbe(long now) { lastProbe = now; return progressSequence; }
    void paused() { failures = 0; }
    boolean complete(Result result, long now, long sequence) {
        // A reply received while the DNS probe was running is stronger evidence than its timeout.
        if (sequence != progressSequence) return false;
        if (result == Result.SUCCESS) {
            received = true; lastProgress = now; failures = 0; progressSequence++;
            return false;
        }
        if (result == Result.UNAVAILABLE) { failures = 0; return false; }
        failures = Math.min(FAILURE_LIMIT, failures + 1);
        while (!restarts.isEmpty() && now - restarts.peekFirst() >= BUDGET_WINDOW_MS) restarts.removeFirst();
        return failures >= FAILURE_LIMIT && restarts.size() < RESTART_LIMIT
                && (restarts.isEmpty() || now - restarts.peekLast() >= RESTART_COOLDOWN_MS);
    }
    void restarting(long now) { restarts.addLast(now); totalRestarts++; }
    int failedProbes() { return failures; }
    int totalRestarts() { return totalRestarts; }
}
