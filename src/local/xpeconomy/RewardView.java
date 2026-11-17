package local.xpeconomy;

/**
 * Read-only view of a player's online-bonus streak for the web page (nothing is settled or paid here).
 * {@code state}: {@code disabled}, {@code none} (no streak), {@code active} (online) or {@code paused} (offline,
 * within the rejoin grace time).
 */
record RewardView(String state, long streak, double nextXp, long intervalSeconds, long remainingSeconds,
                  long graceMinutes) {

    static RewardView of(OnlineRewardState reward, boolean online, boolean enabled, long now, long intervalMillis,
                         long graceMillis, double baseXp, double growth) {
        long interval = Math.max(1L, intervalMillis);
        long intervalSeconds = Math.max(1L, (interval + 999L) / 1000L);
        if (!enabled) return new RewardView("disabled", 0L, 0.0, intervalSeconds, 0L, 0L);
        if (reward == null) return new RewardView("none", 0L, 0.0, intervalSeconds, 0L, 0L);
        long sinceActive = Math.max(0L, now - reward.lastActiveEpochMillis());
        if (online) {
            // Time accrued since the last check counts as if it had been settled already.
            long accumulated = saturatingAdd(Math.max(0L, reward.partialIntervalMillis()), sinceActive);
            long streak = saturatingAdd(Math.max(0L, reward.completedIntervals()), accumulated / interval);
            long remainder = accumulated % interval;
            long remaining = Math.max(1L, (interval - remainder + 999L) / 1000L);
            return new RewardView("active", streak, nextReward(baseXp, growth, streak), intervalSeconds, remaining, 0L);
        }
        long graceLeft = graceMillis - sinceActive;
        if (reward.lastActiveEpochMillis() <= 0L || graceLeft < 0L)
            return new RewardView("none", 0L, 0.0, intervalSeconds, 0L, 0L);
        long streak = Math.max(0L, reward.completedIntervals());
        return new RewardView("paused", streak, nextReward(baseXp, growth, streak), intervalSeconds, 0L,
                (graceLeft + 59_999L) / 60_000L);
    }

    /** XP of the next payout after {@code streak} completed intervals. */
    static double nextReward(double baseXp, double growth, long streak) {
        double reward = baseXp * StrictMath.pow(Math.max(1.0, growth), streak);
        return Double.isFinite(reward) ? reward : Double.MAX_VALUE;
    }

    private static long saturatingAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }
}
