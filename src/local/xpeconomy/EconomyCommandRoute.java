package local.xpeconomy;

import java.util.Arrays;
import java.util.Locale;

record EconomyCommandRoute(String action, String permission, String[] arguments) {
    EconomyCommandRoute { arguments = arguments.clone(); }
    @Override public String[] arguments() { return arguments.clone(); }
    static EconomyCommandRoute parse(String command, String[] args) {
        return switch (command.toLowerCase(Locale.ROOT)) {
            case "money", "bal" -> new EconomyCommandRoute("balance", "xpeconomy.balance", args);
            case "pay" -> new EconomyCommandRoute("pay", "xpeconomy.pay", args);
            case "baltop", "moneytop", "xptop" -> new EconomyCommandRoute("top", "xpeconomy.top", args);
            default -> parse(args);
        };
    }
    static EconomyCommandRoute parse(String[] args) {
        String action = args.length == 0 ? "balance" : args[0].toLowerCase(Locale.ROOT);
        String permission = switch (action) {
            case "balance", "guthaben", "konto", "account", "menu", "web", "goal", "ziel", "privacy", "history", "statement" -> "xpeconomy.balance";
            case "pay", "zahlen", "favorite", "favorit", "request", "requests", "split", "requestblock", "limits" -> "xpeconomy.pay";
            case "top", "rangliste" -> "xpeconomy.top";
            case "cheque", "scheck", "withdraw" -> "xpeconomy.cheque";
            case "bank", "kasse" -> "xpeconomy.bank";
            case "standing", "dauerauftrag" -> "xpeconomy.standing";
            case "term", "festgeld" -> "xpeconomy.term";
            case "fund", "spende" -> "xpeconomy.fund";
            case "admin" -> "xpeconomy.admin";
            default -> "";
        };
        return new EconomyCommandRoute(action, permission,
                args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length));
    }

    /** Every /economy sub-command incl. German aliases, in suggestion order ("web" only with the Core web platform). */
    static final java.util.List<String> ACTIONS = java.util.List.of("account", "konto", "menu", "balance", "guthaben", "web", "goal", "ziel",
            "privacy", "history", "statement", "pay", "zahlen", "favorite", "favorit", "request", "requests", "split", "requestblock", "limits",
            "cheque", "scheck", "bank", "kasse", "standing", "dauerauftrag", "term", "festgeld", "fund", "spende", "top", "rangliste", "admin");

    /** The switch an action belongs to (German aliases included), or null for actions that are always on. */
    static Feature feature(String action) {
        return switch (action) {
            case "account", "konto", "menu" -> Feature.ACCOUNT_MENU;
            case "web" -> Feature.WEB;
            case "goal", "ziel" -> Feature.SAVINGS_GOAL;
            case "privacy" -> Feature.PRIVACY;
            case "history" -> Feature.HISTORY;
            case "statement" -> Feature.STATEMENTS;
            case "pay", "zahlen", "favorite", "favorit" -> Feature.PAYMENTS;
            case "request", "requests", "split", "requestblock" -> Feature.REQUESTS;
            case "limits" -> Feature.LIMITS;
            case "top", "rangliste" -> Feature.LEADERBOARD;
            case "cheque", "scheck", "withdraw" -> Feature.CHEQUES;
            case "bank", "kasse" -> Feature.BANKS;
            case "standing", "dauerauftrag" -> Feature.STANDING_ORDERS;
            case "term", "festgeld" -> Feature.TERM_DEPOSITS;
            case "fund", "spende" -> Feature.FUNDS;
            default -> null;
        };
    }

    /** Sub-commands that open the account dialog for players ("konto" is the German alias of "account"). */
    static boolean isAccountAction(String action) {
        return action.equals("account") || action.equals("konto") || action.equals("menu");
    }

    /** Leaderboard page argument: a positive whole number, otherwise null. */
    static Integer parsePage(String text) {
        if (text == null || !text.trim().matches("\\d{1,9}")) return null;
        int page = Integer.parseInt(text.trim());
        return page <= 0 ? null : page;
    }
}
