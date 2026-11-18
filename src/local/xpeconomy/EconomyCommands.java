package local.xpeconomy;

import Vystorm.vystorm_core.commands.Arg;
import Vystorm.vystorm_core.commands.CommandContext;
import Vystorm.vystorm_core.commands.CommandException;
import Vystorm.vystorm_core.commands.CommandHandler;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.Suggester;
import java.util.ArrayList;
import org.bukkit.command.CommandSender;
import java.util.List;
import java.util.function.Consumer;

/**
 * /money, /pay, /baltop and /economy as Vystorm Core command trees (Core 0.45.0+). Names, root aliases, usage and
 * help come from {@code commands.<command>.*} in the language files ({@code /economy kasse einzahlen},
 * {@code /经验经济 银行 存入}); English always works.
 *
 * <p>Every node hands the English path plus the arguments as typed to {@link XPVaultEconomy#dispatch}, so the
 * existing parsers (amounts, bank names, order IDs, their messages) stay exactly as they were; only keyword
 * arguments (on/off) are declared and arrive as their English key. Arguments are optional here because the old
 * parsers answer missing ones with their own usage texts.
 */
final class EconomyCommands {
    private EconomyCommands() {}

    /** What the trees call: {@link XPVaultEconomy#dispatch} and {@link XPVaultEconomy#complete}. */
    interface Target {
        /** Runs {@code /<command> <args>} with English names; {@code false} = unknown form (usage is shown). */
        boolean dispatch(CommandSender sender, String command, String[] args);

        /** Suggestions for the last of {@code args}. */
        List<String> complete(CommandSender sender, String command, String[] args);

    }

    static Target target(XPVaultEconomy plugin) {
        return new Target() {
            @Override public boolean dispatch(CommandSender sender, String command, String[] args) { return plugin.dispatch(sender, command, args); }
            @Override public List<String> complete(CommandSender sender, String command, String[] args) { return plugin.complete(sender, command, args); }
        };
    }

    /** The four trees; {@code plugin} may be {@code null} in tests that only inspect names, completion and help. */
    static List<CommandSpec> specs(Target plugin) { return specs(plugin, feature -> true); }

    /** The four trees; the (sub)commands of features for which {@code on} is false (asked live) are hidden from help and tab completion. */
    static List<CommandSpec> specs(Target plugin, java.util.function.Predicate<Feature> on) {
        return List.of(
                CommandSpec.command("money").permission("xpeconomy.balance").aliases("bal")
                        .arg(rest(plugin, "money", "player"))
                        .executes(run(plugin, "money")).build(),
                gate(CommandSpec.command("pay"), on, Feature.PAYMENTS).permission("xpeconomy.pay")
                        .arg(word(plugin, "pay", "player"))
                        .arg(rest(plugin, "pay", "amount"))
                        .executes(run(plugin, "pay")).build(),
                gate(CommandSpec.command("baltop"), on, Feature.LEADERBOARD).permission("xpeconomy.top").aliases("moneytop", "xptop")
                        .arg(rest(plugin, "baltop", "page"))
                        .executes(run(plugin, "baltop")).build(),
                economy(plugin, on));
    }

    private static CommandSpec economy(Target p, java.util.function.Predicate<Feature> on) {
        String e = "economy";
        String balance = "xpeconomy.balance", pay = "xpeconomy.pay";
        return CommandSpec.command(e).permission(balance).aliases("xpeconomy", "xpeco")
                .executes(run(p, e))
                .sub("account", s -> gate(s, on, Feature.ACCOUNT_MENU).aliases("menu").permission(balance).arg(rest(p, e, "player")).executes(run(p, e)))
                .sub("balance", s -> s.permission(balance).arg(rest(p, e, "player")).executes(run(p, e)))
                .sub("web", s -> gate(s, on, Feature.WEB).permission(balance).executes(run(p, e)))
                .sub("goal", s -> gate(s, on, Feature.SAVINGS_GOAL).permission(balance).executes(run(p, e))
                        .sub("show", leaf(p))
                        .sub("set", leaf(p, "points", "label"))
                        .sub("clear", s2 -> s2.aliases("remove").executes(run(p, e)))
                        .sub("notify", s2 -> s2.arg(Arg.choice("state", "on", "off").optional()).executes(run(p, e))))
                .sub("privacy", s -> gate(s, on, Feature.PRIVACY).permission(balance).arg(Arg.choice("state", "on", "off").optional()).executes(run(p, e)))
                .sub("history", s -> gate(s, on, Feature.HISTORY).permission(balance).executes(run(p, e))
                        .sub("export", leaf(p)))
                .sub("statement", s -> gate(s, on, Feature.STATEMENTS).permission(balance).arg(rest(p, e, "days")).executes(run(p, e))
                        .sub("summary", leaf(p, "days"))
                        .sub("export", leaf(p, "days")))
                .sub("pay", s -> gate(s, on, Feature.PAYMENTS).permission(pay).arg(word(p, e, "player")).arg(rest(p, e, "amount")).executes(run(p, e)))
                .sub("favorite", s -> gate(s, on, Feature.PAYMENTS).permission(pay).executes(run(p, e))
                        .sub("list", leaf(p))
                        .sub("add", leaf(p, "player"))
                        .sub("remove", leaf(p, "player"))
                        .sub("pay", leaf(p, "favorite", "amount")))
                .sub("request", s -> gate(s, on, Feature.REQUESTS).permission(pay).arg(word(p, e, "player")).arg(word(p, e, "amount")).arg(rest(p, e, "purpose")).executes(run(p, e)))
                .sub("requests", s -> gate(s, on, Feature.REQUESTS).permission(pay).executes(run(p, e))
                        .sub("list", leaf(p))
                        .sub("accept", leaf(p, "id"))
                        .sub("decline", leaf(p, "id"))
                        .sub("cancel", leaf(p, "id")))
                .sub("split", s -> gate(s, on, Feature.REQUESTS).permission(pay).arg(word(p, e, "amount")).arg(word(p, e, "players")).arg(rest(p, e, "purpose")).executes(run(p, e)))
                .sub("requestblock", s -> gate(s, on, Feature.REQUESTS).permission(pay).arg(word(p, e, "player")).arg(Arg.choice("state", "on", "off").optional()).executes(run(p, e)))
                .sub("limits", s -> gate(s, on, Feature.LIMITS).permission(pay).arg(word(p, e, "single")).arg(rest(p, e, "daily")).executes(run(p, e)))
                .sub("cheque", s -> gate(s, on, Feature.CHEQUES).aliases("withdraw").permission("xpeconomy.cheque").arg(word(p, e, "amount")).arg(rest(p, e, "count")).executes(run(p, e)))
                .sub("bank", s -> gate(s, on, Feature.BANKS).permission("xpeconomy.bank").executes(run(p, e))
                        .sub("list", leaf(p))
                        .sub("info", leaf(p, "bank"))
                        .sub("create", leaf(p, "name"))
                        .sub("deposit", leaf(p, "bank", "amount"))
                        .sub("withdraw", leaf(p, "bank", "amount"))
                        .sub("add", leaf(p, "bank", "player"))
                        .sub("remove", leaf(p, "bank", "player"))
                        .sub("policy", leaf(p, "bank", "player", "deposit", "withdraw", "single", "daily"))
                        .sub("history", leaf(p, "bank", "page"))
                        .sub("delete", leaf(p, "bank")))
                .sub("standing", s -> gate(s, on, Feature.STANDING_ORDERS).permission("xpeconomy.standing").executes(run(p, e))
                        .sub("list", leaf(p))
                        .sub("add", leaf(p, "player", "amount", "interval", "runs"))
                        .sub("pause", leaf(p, "id"))
                        .sub("resume", leaf(p, "id"))
                        .sub("cancel", leaf(p, "id")))
                .sub("fund", s -> gate(s, on, Feature.FUNDS).permission("xpeconomy.fund").executes(run(p, e))
                        .sub("list", leaf(p))
                        .sub("create", leaf(p))
                        .sub("cancel", leaf(p, "id")))
                .sub("term", s -> gate(s, on, Feature.TERM_DEPOSITS).permission("xpeconomy.term").executes(run(p, e))
                        .sub("list", leaf(p))
                        .sub("open", leaf(p))
                        .sub("withdraw", leaf(p, "id")))
                .sub("top", s -> gate(s, on, Feature.LEADERBOARD).permission("xpeconomy.top").arg(rest(p, e, "page")).executes(run(p, e)))
                .sub("admin", s -> s.permission("xpeconomy.admin")
                        .sub("give", leaf(p, "player", "amount"))
                        .sub("take", leaf(p, "player", "amount"))
                        .sub("set", leaf(p, "player", "amount"))
                        .sub("history", leaf(p, "player", "count"))
                        .sub("info", leaf(p))
                        .sub("reload", leaf(p)))
                .build();
    }

    /**
     * Lists a command or subcommand in help and tab completion only while its switch is on (asked at every
     * completion, so a switch applies at once); while off it still answers the shared off message.
     */
    static CommandSpec.Builder gate(CommandSpec.Builder node, java.util.function.Predicate<Feature> on, Feature feature) {
        return node.visibleWhen(() -> on.test(feature));
    }

    /** An /economy leaf with optional free-text arguments (the last one takes the rest of the line). */
    private static Consumer<CommandSpec.Builder> leaf(Target p, String... args) {
        return s -> {
            for (int i = 0; i < args.length; i++)
                s.arg(i == args.length - 1 ? rest(p, "economy", args[i]) : word(p, "economy", args[i]));
            s.executes(run(p, "economy"));
        };
    }

    private static Arg<String> word(Target p, String command, String id) {
        return Arg.word(id).optional().suggests(suggester(p, command));
    }

    private static Arg<String> rest(Target p, String command, String id) {
        return Arg.greedy(id).optional().suggests(suggester(p, command));
    }

    private static Suggester suggester(Target p, String command) {
        return (ctx, prefix) -> p == null ? List.of() : p.complete(ctx.sender(), command, english(ctx));
    }

    private static CommandHandler run(Target p, String command) {
        return ctx -> {
            if (!p.dispatch(ctx.sender(), command, english(ctx))) throw new CommandException("common.usage", "usage", ctx.usage());
        };
    }

    /**
     * The invocation as the old executor saw it: English subcommand ids, then the typed arguments unchanged except
     * keyword arguments, which become their English key ({@code an} → {@code on}).
     */
    static String[] english(CommandContext ctx) {
        List<String> out = new ArrayList<>(ctx.path());
        String[] raw = ctx.rawArgs();
        int i = ctx.path().size();
        for (Arg<?> arg : ctx.node().args()) {
            if (i >= raw.length || arg.kind() == Arg.Kind.GREEDY) break;
            Object value = ctx.get(arg.id());
            out.add(arg.kind() == Arg.Kind.CHOICE && value instanceof String key ? key : raw[i]);
            i++;
        }
        while (i < raw.length) out.add(raw[i++]);
        return out.toArray(String[]::new);
    }
}
