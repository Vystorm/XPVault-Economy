package local.xpeconomy;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingInfo;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.Plugin;

/**
 * The ranking {@code xpvault:balance} in Core's placeholders (Core 0.67.0, capability {@code placeholder-rankings}):
 * the richest players by XP balance. It reads the same sorted leaderboard as {@code /baltop}, so offline players are
 * listed, players who hid their balance ({@code /economy privacy}, while {@code privacy.enabled} is on) and accounts
 * hidden by {@code top.hide-name-prefixes} are left out, and an unreadable privacy file hides everyone, exactly as
 * there. Nothing is read from disk: a missing or stale leaderboard is rebuilt in the background as for the other
 * placeholders, and until then the ranking shows the last one or stays empty.
 *
 * <p>This is the only class that names Core's ranking types. An older Core lacks them, so it is only touched after
 * {@code CoreApi.has("placeholder-rankings")} ({@link XPVaultEconomy#rankings}).
 */
final class XpVaultRankings {
    static final String BALANCE = "balance";

    private XpVaultRankings() {}

    /** Declares the ranking when {@code on}, otherwise removes it (switch turned off by a reload). */
    static void apply(Plugin plugin, PlaceholderInterface registry, boolean on, Supplier<BalanceRanking> ranking, UnaryOperator<String> ref) {
        if (!on) {
            registry.namespace(XpVaultPlaceholders.NAMESPACE).ifPresent(ns -> ns.removeRanking(BALANCE));
            return;
        }
        registry.register(plugin, XpVaultPlaceholders.NAMESPACE).ranking(BALANCE,
                RankingInfo.of(ref.apply("rankings.balance.label"), ref.apply("rankings.balance.description")),
                new Balances(ranking));
    }

    /** The leaderboard as rows; ranks and values of players outside the best 100 come from the same leaderboard. */
    record Balances(Supplier<BalanceRanking> ranking) implements RankingSource {
        @Override public List<Row> top(int n) {
            BalanceRanking current = ranking.get();
            if (current == null) return List.of();
            List<Row> rows = new ArrayList<>(Math.min(n, current.size()));
            for (int position = 1; position <= n; position++) {
                BalanceRanking.Entry entry = current.at(position);
                if (entry == null) break;
                rows.add(new Row(entry.uuid(), entry.name(), entry.balance()));
            }
            return rows;
        }

        @Override public OptionalDouble value(UUID player) {
            BalanceRanking current = ranking.get();
            return current == null ? OptionalDouble.empty()
                    : current.find(player).map(entry -> OptionalDouble.of(entry.balance())).orElse(OptionalDouble.empty());
        }

        @Override public OptionalInt rank(UUID player) {
            BalanceRanking current = ranking.get();
            return current == null ? OptionalInt.empty()
                    : current.find(player).map(entry -> OptionalInt.of(entry.rank())).orElse(OptionalInt.empty());
        }
    }
}
