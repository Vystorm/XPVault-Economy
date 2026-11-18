package local.xpeconomy;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The values of XPVaultEconomy in Core's placeholder registry, namespace {@code xpvault}. A value of a switched-off
 * feature is empty. Balance, level and account count read the concurrent account map and also answer off the server
 * thread; everything else is answered on the server thread only.
 *
 * <pre>
 * %vystorm_xpvault_balance%            {xpvault:balance}             1234
 * %vystorm_xpvault_balance_formatted%  {xpvault:balance_formatted}   1,234 XP points
 * %vystorm_xpvault_top_1_name%         {xpvault:top:1:name}          name on place 1 (top_&lt;n&gt;_balance: the balance)
 * level, rank, bank_total, banks_count, goal_percent, reward_streak, reward_next_xp, reward_remaining, accounts
 * </pre>
 */
final class XpVaultPlaceholders {
    static final String NAMESPACE = "xpvault";

    /** What the values read, at call time (the plugin's fields change on reload). */
    interface Source {
        boolean on(Feature feature);
        long balance(OfflinePlayer player);
        String formatted(OfflinePlayer player, long points);
        /** The sorted leaderboard if one is ready (never builds one on the caller's thread), else null. */
        BalanceRanking ranking();
        /** The balances of the banks the player is a member of. */
        List<Long> banks(UUID player);
        /** Saving goal progress 0-100, or -1 without a goal. */
        int goalPercent(Player player);
        RewardView reward(UUID player, boolean online);
        int accounts();
    }

    private XpVaultPlaceholders() {}

    static PlaceholderNamespace register(PlaceholderInterface registry, Plugin owner, Source source, UnaryOperator<String> describe) {
        PlaceholderNamespace ns = registry.register(owner, NAMESPACE);
        ns.add(PlaceholderValue.viewer("balance", source::balance).asyncSafe().description(describe.apply("placeholder.balance")));
        ns.value("balance_formatted", describe.apply("placeholder.balance_formatted"), viewer -> source.formatted(viewer, source.balance(viewer)));
        ns.add(PlaceholderValue.viewer("level", viewer -> BalanceRanking.levelForPoints(source.balance(viewer))).asyncSafe()
                .description(describe.apply("placeholder.level")));
        ns.value("rank", describe.apply("placeholder.rank"), viewer -> {
            if (!source.on(Feature.LEADERBOARD)) return "";
            BalanceRanking ranking = source.ranking();
            return ranking == null ? "" : ranking.find(viewer.getUniqueId()).map(entry -> (Object) entry.rank()).orElse("");
        });
        ns.resolver("top", describe.apply("placeholder.top"), "<place>_<name|balance>", context -> {
            if (!source.on(Feature.LEADERBOARD)) return "";
            String part = context.argument(1, "name");
            if (!part.equalsIgnoreCase("name") && !part.equalsIgnoreCase("balance")) return null;
            BalanceRanking ranking = source.ranking();
            BalanceRanking.Entry entry = ranking == null ? null : ranking.at(context.intArgument(0, 1));
            if (entry == null) return "";
            return part.equalsIgnoreCase("name") ? entry.name() : entry.balance();
        });
        ns.value("bank_total", describe.apply("placeholder.bank_total"), viewer -> source.on(Feature.BANKS)
                ? source.banks(viewer.getUniqueId()).stream().mapToLong(Long::longValue).sum() : "");
        ns.value("banks_count", describe.apply("placeholder.banks_count"), viewer -> source.on(Feature.BANKS)
                ? source.banks(viewer.getUniqueId()).size() : "");
        ns.player("goal_percent", describe.apply("placeholder.goal_percent"), player -> {
            if (!source.on(Feature.SAVINGS_GOAL)) return "";
            int percent = source.goalPercent(player);
            return percent < 0 ? "" : percent;
        });
        ns.value("reward_streak", describe.apply("placeholder.reward_streak"), viewer -> source.on(Feature.ONLINE_REWARDS)
                ? source.reward(viewer.getUniqueId(), viewer.isOnline()).streak() : "");
        ns.value("reward_next_xp", describe.apply("placeholder.reward_next_xp"), viewer -> source.on(Feature.ONLINE_REWARDS)
                ? (long) Math.floor(source.reward(viewer.getUniqueId(), viewer.isOnline()).nextXp()) : "");
        ns.value("reward_remaining", describe.apply("placeholder.reward_remaining"), viewer -> source.on(Feature.ONLINE_REWARDS)
                ? source.reward(viewer.getUniqueId(), viewer.isOnline()).remainingSeconds() : "");
        ns.add(PlaceholderValue.global("accounts", source::accounts).asyncSafe().description(describe.apply("placeholder.accounts")));
        return ns;
    }
}
