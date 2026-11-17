package local.xpeconomy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Locale;
import java.util.UUID;

/** JSON of the web page (language-free numbers; texts come from the page's language files). No Bukkit access. */
final class EconomyWebJson {
    private EconomyWebJson() {
    }

    /** Own account: balance, vanilla level progress, limit and online-bonus view. */
    static JsonObject account(String name, boolean known, boolean online, long balance, long maximum, RewardView reward) {
        return account(name, known, online, balance, maximum, reward, null);
    }

    static JsonObject account(String name, boolean known, boolean online, long balance, long maximum, RewardView reward,
                              PersonalEconomyPrefs.Goal goal) {
        JsonObject json = new JsonObject();
        json.addProperty("name", name == null ? "" : name);
        json.addProperty("known", known);
        json.addProperty("online", online);
        json.addProperty("balance", balance);
        json.addProperty("max", maximum);
        long[] level = BalanceRanking.levelProgress(balance);
        json.addProperty("level", level[0]);
        json.addProperty("levelXp", level[1]);
        json.addProperty("levelNeed", level[2]);
        if (goal == null) json.add("goal", null);
        else {
            JsonObject goalJson = new JsonObject();
            goalJson.addProperty("target", goal.target());
            goalJson.addProperty("label", goal.label());
            goalJson.addProperty("notify", goal.notifyOnReach());
            goalJson.addProperty("progress", Math.min(balance, goal.target()));
            goalJson.addProperty("remaining", Math.max(0L, goal.target() - balance));
            json.add("goal", goalJson);
        }
        json.add("reward", reward(reward));
        return json;
    }

    static JsonObject reward(RewardView reward) {
        JsonObject json = new JsonObject();
        json.addProperty("state", reward.state());
        json.addProperty("streak", reward.streak());
        json.addProperty("nextXp", Math.round(reward.nextXp() * 100.0) / 100.0);
        json.addProperty("intervalSeconds", reward.intervalSeconds());
        json.addProperty("remainingSeconds", reward.remainingSeconds());
        json.addProperty("graceMinutes", reward.graceMinutes());
        return json;
    }

    /** One leaderboard page; {@code viewer}'s row is flagged, their rank is added when it is on another page. */
    static JsonObject top(BalanceRanking ranking, int requested, int pageSize, UUID viewer, long now) {
        BalanceRanking.Page page = ranking.page(requested, pageSize);
        JsonObject json = new JsonObject();
        json.addProperty("page", page.page());
        json.addProperty("pages", page.pages());
        json.addProperty("total", page.total());
        json.addProperty("pageSize", Math.max(1, pageSize));
        json.addProperty("ageSeconds", Math.max(0L, (now - ranking.createdAtMillis()) / 1000L));
        JsonArray entries = new JsonArray();
        boolean ownOnPage = false;
        for (BalanceRanking.Entry entry : page.entries()) {
            boolean own = entry.uuid().equals(viewer);
            ownOnPage |= own;
            entries.add(entry(entry, own));
        }
        json.add("entries", entries);
        BalanceRanking.Entry own = ranking.find(viewer).orElse(null);
        json.add("own", own == null ? null : entry(own, true));
        json.addProperty("ownOnPage", ownOnPage);
        return json;
    }

    private static JsonObject entry(BalanceRanking.Entry entry, boolean own) {
        JsonObject json = new JsonObject();
        json.addProperty("rank", entry.rank());
        json.addProperty("name", entry.name());
        json.addProperty("balance", entry.balance());
        json.addProperty("level", BalanceRanking.levelForPoints(entry.balance()));
        json.addProperty("own", own);
        return json;
    }

    /** A string field of the request body (a JSON number counts as its text); null when missing or too long. */
    static String text(JsonElement body, String field, int maxLength) {
        if (body == null || !body.isJsonObject()) return null;
        JsonElement value = body.getAsJsonObject().get(field);
        if (value == null || !value.isJsonPrimitive()) return null;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return null;
        String text = primitive.isNumber() ? primitive.getAsBigDecimal().toPlainString() : primitive.getAsString();
        text = text.strip();
        if (text.length() > maxLength) return null;
        for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i))) return null;
        return text;
    }

    /** Admin action of the web form: give, take or set; null for anything else. */
    static String adminAction(String text) {
        if (text == null) return null;
        String action = text.toLowerCase(Locale.ROOT);
        return action.equals("give") || action.equals("take") || action.equals("set") ? action : null;
    }
}
