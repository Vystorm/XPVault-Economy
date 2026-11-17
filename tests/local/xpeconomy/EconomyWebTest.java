package local.xpeconomy;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Web page helpers: bonus view, JSON, body parsing and the once-per-request-id guard for payments. */
class EconomyWebTest {
    private static final UUID ALEX = new UUID(0L, 1L);
    private static final UUID BEA = new UUID(0L, 2L);

    private static OnlineRewardState reward(long completed, long partialMillis, long lastActive) {
        return new OnlineRewardState(ALEX, "Alex", completed, 0.0, partialMillis, lastActive);
    }

    // ---- online bonus view ----

    @Test void activeStreakCountsTimeSinceTheLastCheckWithoutSettlingIt() {
        // 20 s already accrued, last check 25 s ago, interval 60 s: 45 s done, 15 s to go.
        var view = RewardView.of(reward(3, 20_000L, 100_000L), true, true, 125_000L, 60_000L, 1_800_000L, 2.0, 1.02);
        assertEquals("active", view.state());
        assertEquals(3L, view.streak());
        assertEquals(15L, view.remainingSeconds());
        assertEquals(60L, view.intervalSeconds());
        assertEquals(2.0 * Math.pow(1.02, 3), view.nextXp(), 1e-9);
    }

    @Test void overdueIntervalsCountAsCompletedForTheDisplay() {
        var view = RewardView.of(reward(0, 50_000L, 0L + 1L), true, true, 20_001L, 60_000L, 0L, 2.0, 1.0);
        assertEquals(1L, view.streak(), "50 s + 20 s = one full interval");
        assertEquals(50L, view.remainingSeconds());
    }

    @Test void offlineStreakIsPausedWithinTheGraceTimeThenGone() {
        var paused = RewardView.of(reward(5, 0L, 1_000L), false, true, 1_000L + 10 * 60_000L, 60_000L, 30 * 60_000L, 2.0, 1.02);
        assertEquals("paused", paused.state());
        assertEquals(5L, paused.streak());
        assertEquals(20L, paused.graceMinutes());
        var gone = RewardView.of(reward(5, 0L, 1_000L), false, true, 1_000L + 31 * 60_000L, 60_000L, 30 * 60_000L, 2.0, 1.02);
        assertEquals("none", gone.state());
    }

    @Test void disabledAndMissingStates() {
        assertEquals("disabled", RewardView.of(reward(1, 0, 1), true, false, 2, 60_000L, 0L, 2.0, 1.02).state());
        assertEquals("none", RewardView.of(null, true, true, 2, 60_000L, 0L, 2.0, 1.02).state());
        assertTrue(Double.isFinite(RewardView.nextReward(2.0, 1.2, 100_000L)), "huge streaks stay finite");
    }

    // ---- JSON ----

    @Test void accountJsonCarriesBalanceLevelProgressAndReward() {
        var json = EconomyWebJson.account("Alex", true, true, 30L, 1_000L,
                RewardView.of(null, true, true, 0L, 60_000L, 0L, 2.0, 1.02));
        assertEquals(30L, json.get("balance").getAsLong());
        // 7 + 9 = 16 points for level 2, 14 of the 11 + … : level 2 needs 11, 30 - 16 = 14 >= 11 → level 3 with 3 of 13.
        assertEquals(3L, json.get("level").getAsLong());
        assertEquals(3L, json.get("levelXp").getAsLong());
        assertEquals(13L, json.get("levelNeed").getAsLong());
        assertEquals("none", json.getAsJsonObject("reward").get("state").getAsString());
        assertTrue(json.get("online").getAsBoolean());
        assertArrayEquals(new long[]{0, 0, 7}, BalanceRanking.levelProgress(0));
        assertEquals(30, BalanceRanking.levelForPoints(1395));
    }

    @Test void leaderboardJsonFlagsOwnRowAndAddsOwnRankFromOtherPages() {
        var accounts = new java.util.ArrayList<StoredAccount>();
        for (int i = 1; i <= 12; i++) accounts.add(new StoredAccount(new UUID(0L, i), "P" + i, 1_000L - i, true));
        var ranking = BalanceRanking.of(accounts, 1_000L);
        var first = EconomyWebJson.top(ranking, 1, 5, new UUID(0L, 2), 4_000L);
        assertEquals(1, first.get("page").getAsInt());
        assertEquals(3, first.get("pages").getAsInt());
        assertEquals(12, first.get("total").getAsInt());
        assertEquals(3L, first.get("ageSeconds").getAsLong());
        assertTrue(first.getAsJsonArray("entries").get(1).getAsJsonObject().get("own").getAsBoolean());
        assertTrue(first.get("ownOnPage").getAsBoolean());
        var elsewhere = EconomyWebJson.top(ranking, 1, 5, new UUID(0L, 11), 1_000L);
        assertFalse(elsewhere.get("ownOnPage").getAsBoolean());
        assertEquals(11, elsewhere.getAsJsonObject("own").get("rank").getAsInt());
        var stranger = EconomyWebJson.top(ranking, 9, 5, new UUID(9L, 9L), 1_000L);
        assertEquals(3, stranger.get("page").getAsInt(), "pages are clamped like /baltop");
        assertEquals(JsonNull.INSTANCE, stranger.get("own"));
    }

    @Test void bodyFieldsAreStrictStrings() {
        var body = JsonParser.parseString("{\"target\":\" Alex \",\"amount\":25,\"bad\":true,\"ctl\":\"a\\u0000b\",\"long\":\"xxxxxxxxxx\"}");
        assertEquals("Alex", EconomyWebJson.text(body, "target", 64));
        assertEquals("25", EconomyWebJson.text(body, "amount", 24), "a JSON number is read as its text, then parsed like /pay");
        assertNull(EconomyWebJson.text(body, "bad", 10));
        assertNull(EconomyWebJson.text(body, "ctl", 10));
        assertNull(EconomyWebJson.text(body, "long", 5));
        assertNull(EconomyWebJson.text(body, "missing", 5));
        assertNull(EconomyWebJson.text(JsonParser.parseString("[1]"), "target", 5));
        assertEquals("25.5", EconomyWebJson.text(JsonParser.parseString("{\"a\":25.5}"), "a", 10), "decimals reach parsePoints and fail there");
        assertEquals("give", EconomyWebJson.adminAction("GIVE"));
        assertNull(EconomyWebJson.adminAction("reload"));
        assertNull(EconomyWebJson.adminAction(null));
    }

    // ---- once per request id ----

    private static <T> T direct(java.util.concurrent.Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception checked) {
            throw new IllegalStateException(checked);
        }
    }

    @Test void sameRequestIdRunsOnceAndRepeatsTheAnswer() {
        var dedupe = new RequestDedupe(60_000L, 16, 1_000L, () -> 0L);
        AtomicInteger runs = new AtomicInteger();
        String first = dedupe.run(ALEX, "req-00000001", EconomyWebTest::direct, () -> "paid " + runs.incrementAndGet());
        String again = dedupe.run(ALEX, "req-00000001", EconomyWebTest::direct, () -> "paid " + runs.incrementAndGet());
        assertEquals("paid 1", first);
        assertEquals("paid 1", again);
        assertEquals("paid 2", dedupe.run(BEA, "req-00000001", EconomyWebTest::direct, () -> "paid " + runs.incrementAndGet()),
                "ids are per player");
        assertEquals("paid 3", dedupe.run(ALEX, null, EconomyWebTest::direct, () -> "paid " + runs.incrementAndGet()),
                "no id: runs every time");
    }

    @Test void errorsOfTheFirstRunAreRepeatedToo() {
        var dedupe = new RequestDedupe(60_000L, 16, 1_000L, () -> 0L);
        AtomicInteger runs = new AtomicInteger();
        java.util.concurrent.Callable<String> failing = () -> {
            runs.incrementAndGet();
            throw new IllegalArgumentException("Not enough XP points.");
        };
        assertThrows(IllegalArgumentException.class, () -> dedupe.run(ALEX, "req-00000002", EconomyWebTest::direct, failing));
        var repeated = assertThrows(IllegalArgumentException.class, () -> dedupe.run(ALEX, "req-00000002", EconomyWebTest::direct, failing));
        assertEquals("Not enough XP points.", repeated.getMessage());
        assertEquals(1, runs.get());
    }

    @Test void aTaskThatNeverStartedCanBeRetried() {
        var dedupe = new RequestDedupe(60_000L, 16, 1_000L, () -> 0L);
        AtomicInteger runs = new AtomicInteger();
        // The server thread did not pick the task up in time: the executor gives up without running it.
        assertThrows(IllegalStateException.class, () -> dedupe.<String>run(ALEX, "req-00000003", task -> {
            throw new IllegalStateException("503 busy");
        }, () -> "paid " + runs.incrementAndGet()));
        assertEquals(0, dedupe.size());
        assertEquals("paid 1", dedupe.run(ALEX, "req-00000003", EconomyWebTest::direct, () -> "paid " + runs.incrementAndGet()));
    }

    @Test void aRetryAfterATimeoutGetsTheRealOutcomeOfTheStillRunningTask() throws Exception {
        var dedupe = new RequestDedupe(60_000L, 16, 2_000L, () -> 0L);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        // The handler times out while the task is running on the "server thread"; the task finishes afterwards.
        assertThrows(IllegalStateException.class, () -> dedupe.<String>run(ALEX, "req-00000004", task -> {
            CompletableFuture.runAsync(() -> direct(task));
            try {
                started.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            throw new IllegalStateException("503 busy");
        }, () -> {
            started.countDown();
            release.await(1, TimeUnit.SECONDS);
            return "paid " + runs.incrementAndGet();
        }));
        release.countDown();
        assertEquals("paid 1", dedupe.run(ALEX, "req-00000004", EconomyWebTest::direct, () -> "paid " + runs.incrementAndGet()));
        assertEquals(1, runs.get());
    }

    @Test void entriesExpireAndStayBounded() {
        AtomicLong now = new AtomicLong();
        var dedupe = new RequestDedupe(1_000L, 3, 100L, now::get);
        for (int i = 0; i < 10; i++) dedupe.run(ALEX, "req-0000000" + i, EconomyWebTest::direct, () -> "x");
        assertTrue(dedupe.size() <= 3);
        now.set(5_000L);
        dedupe.run(ALEX, "req-00000099", EconomyWebTest::direct, () -> "x");
        assertEquals(1, dedupe.size());
        assertTrue(RequestDedupe.validId("0123456789abcdef"));
        for (String bad : List.of("short", "has space x", "x".repeat(65), "../../etc")) assertFalse(RequestDedupe.validId(bad), bad);
    }
}
