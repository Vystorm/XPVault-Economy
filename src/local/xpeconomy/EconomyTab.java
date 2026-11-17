package local.xpeconomy;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;

/** Tab suggestions for /economy on every level, filtered by permission and prefix (case-insensitive), memory only. */
final class EconomyTab {
    private EconomyTab() {
    }

    static List<String> complete(CommandSender sender, String[] args, boolean web, String localized, Supplier<List<String>> online) {
        return complete(sender, args, web, localized, online, List::of);
    }

    /** {@code openRequests} supplies the IDs of the sender's open incoming payment requests (for accept/decline). */
    static List<String> complete(CommandSender sender, String[] args, boolean web, String localized, Supplier<List<String>> online,
                                 Supplier<List<String>> openRequests) {
        return complete(sender, args, web, localized, online, openRequests, List::of, List::of);
    }

    /** {@code banks}: names of the sender's XP banks; {@code orders}: IDs of the sender's standing orders. */
    static List<String> complete(CommandSender sender, String[] args, boolean web, String localized, Supplier<List<String>> online,
                                 Supplier<List<String>> openRequests, Supplier<List<String>> banks, Supplier<List<String>> orders) {
        return complete(sender, args, web, localized, online, openRequests, banks, orders, List::of);
    }

    static List<String> complete(CommandSender sender, String[] args, boolean web, String localized, Supplier<List<String>> online,
                                 Supplier<List<String>> openRequests, Supplier<List<String>> banks, Supplier<List<String>> orders,
                                 Supplier<List<String>> outgoingRequests) {
        if (args.length <= 1) {
            List<String> options = new java.util.ArrayList<>();
            if (sender.hasPermission("xpeconomy.balance") && localized != null) {
                if (!EconomyCommandRoute.ACTIONS.contains(localized.toLowerCase(Locale.ROOT))) options.add(localized);
            }
            for (String action : EconomyCommandRoute.ACTIONS) {
                if (action.equals("web") && !web) continue;
                if (sender.hasPermission(EconomyCommandRoute.parse(new String[]{action}).permission())) options.add(action);
            }
            return starts(options, args.length == 0 ? "" : args[0]);
        }
        EconomyCommandRoute route = EconomyCommandRoute.parse(args);
        if (route.permission().isEmpty() || !sender.hasPermission(route.permission())) return List.of();
        String[] nested = route.arguments();
        String last = nested[nested.length - 1];
        switch (route.action()) {
            case "admin" -> {
                if (nested.length == 1) return starts(List.of("give", "take", "set", "history", "info", "reload"), last);
                if (nested.length == 2 && List.of("give", "take", "set", "history").contains(nested[0].toLowerCase(Locale.ROOT))) return starts(online.get(), last);
                if (nested.length == 3 && List.of("give", "take", "set").contains(nested[0].toLowerCase(Locale.ROOT))) return starts(List.of("100", "1000", "10000"), last);
                if (nested.length == 3 && nested[0].equalsIgnoreCase("history")) return starts(List.of("10", "25", "50"), last);
                return List.of();
            }
            case "goal", "ziel" -> {
                if (nested.length == 1) return starts(List.of("show", "set", "clear", "notify"), last);
                if (nested.length == 2 && nested[0].equalsIgnoreCase("notify")) return starts(List.of("on", "off"), last);
                if (nested.length == 2 && nested[0].equalsIgnoreCase("set")) return starts(List.of("1000", "10000"), last);
                return List.of();
            }
            case "favorite", "favorit" -> {
                if (nested.length == 1) return starts(List.of("list", "add", "remove", "pay"), last);
                if (nested.length == 2 && (nested[0].equalsIgnoreCase("add") || nested[0].equalsIgnoreCase("remove")
                        || nested[0].equalsIgnoreCase("pay"))) return starts(online.get(), last);
                if (nested.length == 3 && nested[0].equalsIgnoreCase("pay")) return starts(List.of("10", "100", "1000"), last);
                return List.of();
            }
            case "privacy" -> { return nested.length == 1 ? starts(List.of("on", "off"), last) : List.of(); }
            case "history" -> { return nested.length == 1 ? starts(List.of("export"), last) : List.of(); }
            case "statement" -> {
                if (nested.length == 1) return starts(List.of("1", "7", "30", "export", "summary"), last);
                return nested.length == 2 && (nested[0].equalsIgnoreCase("export") || nested[0].equalsIgnoreCase("summary")) ? starts(List.of("1", "7", "30"), last) : List.of();
            }
            case "requests" -> {
                if (nested.length == 1) return starts(List.of("list", "accept", "decline", "cancel"), last);
                if (nested.length == 2 && nested[0].equalsIgnoreCase("cancel")) return starts(outgoingRequests.get(), last);
                if (nested.length == 2 && (nested[0].equalsIgnoreCase("accept") || nested[0].equalsIgnoreCase("decline")))
                    return starts(openRequests.get(), last);
                return List.of();
            }
            case "requestblock" -> {
                if (nested.length == 1) return starts(online.get(), last);
                return nested.length == 2 ? starts(List.of("on", "off"), last) : List.of();
            }
            case "request" -> {
                if (nested.length == 1) return starts(online.get(), last);
                return nested.length == 2 ? starts(List.of("100", "1000"), last) : List.of();
            }
            case "split" -> {
                if (nested.length == 1) return starts(List.of("100", "1000"), last);
                return nested.length == 2 ? splitPayers(online.get(), last) : List.of();
            }
            case "limits" -> { return nested.length <= 2 ? starts(List.of("100", "1000", "10000"), last) : List.of(); }
            case "cheque", "scheck", "withdraw" -> {
                if (nested.length == 1) return starts(List.of("100", "500", "1000"), last);
                return nested.length == 2 ? starts(List.of("1", "5", "10"), last) : List.of();
            }
            case "bank", "kasse" -> {
                if (nested.length == 1) return starts(List.of("list", "info", "create", "deposit", "withdraw", "add", "remove", "delete"), last);
                String sub = nested[0].toLowerCase(Locale.ROOT);
                if (nested.length == 2 && List.of("info", "deposit", "withdraw", "add", "remove", "delete").contains(sub)) return starts(banks.get(), last);
                if (nested.length == 3 && (sub.equals("deposit") || sub.equals("withdraw"))) return starts(List.of("100", "1000", "10k"), last);
                if (nested.length == 3 && (sub.equals("add") || sub.equals("remove"))) return starts(online.get(), last);
                return List.of();
            }
            case "standing", "dauerauftrag" -> {
                if (nested.length == 1) return starts(List.of("list", "add", "pause", "resume", "cancel"), last);
                String sub = nested[0].toLowerCase(Locale.ROOT);
                if (nested.length == 2 && List.of("pause", "resume", "cancel").contains(sub)) return starts(orders.get(), last);
                if (!sub.equals("add")) return List.of();
                if (nested.length == 2) return starts(online.get(), last);
                if (nested.length == 3) return starts(List.of("100", "1000"), last);
                if (nested.length == 4) return starts(List.of("1h", "12h", "1d", "7d"), last);
                return nested.length == 5 ? starts(List.of("0", "4", "10"), last) : List.of();
            }
            case "top", "rangliste" -> { return nested.length == 1 ? starts(List.of("1", "2", "3"), last) : List.of(); }
            case "pay", "zahlen" -> {
                if (nested.length == 1) return starts(online.get(), last);
                return nested.length == 2 ? starts(List.of("10", "100", "1000"), last) : List.of();
            }
            default -> {
                if (nested.length == 1 && sender.hasPermission("xpeconomy.balance.others")) return starts(online.get(), last);
                return List.of();
            }
        }
    }

    /** Complete only the last comma-separated payer, retaining the already typed list. */
    static List<String> splitPayers(List<String> online, String typed) {
        int comma = typed.lastIndexOf(',');
        String head = typed.substring(0, comma + 1);
        String prefix = typed.substring(comma + 1);
        String[] selected = head.isEmpty() ? new String[0] : head.substring(0, head.length() - 1).split(",", -1);
        if (selected.length >= 16) return List.of();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (String name : selected) {
            if (name.isEmpty() || !used.add(name.toLowerCase(Locale.ROOT))) return List.of();
        }
        return starts(online, prefix).stream()
                .filter(name -> !used.contains(name.toLowerCase(Locale.ROOT)))
                .map(name -> head + name).toList();
    }

    static List<String> starts(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
