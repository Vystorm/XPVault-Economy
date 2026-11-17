package local.xpeconomy;

import java.util.UUID;

record OnlineRewardState(
        UUID uuid,
        String name,
        long completedIntervals,
        double fractionalXp,
        long partialIntervalMillis,
        long lastActiveEpochMillis) {
}
