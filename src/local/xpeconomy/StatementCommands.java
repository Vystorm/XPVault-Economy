package local.xpeconomy;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core.compat.dialog.DialogButton;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Personal journal summaries with period selection and export of the displayed snapshot. */
final class StatementCommands {
    private final XPVaultEconomy plugin;
    private final API core;
    private final TransactionHistory history;
    private final AtomicFilesInterface files;
    private final Lang lang;

    /** @param files Core's file part, {@code core.data().files()} */
    StatementCommands(XPVaultEconomy plugin, API core, TransactionHistory history, AtomicFilesInterface files) {
        this.plugin = plugin; this.core = core; this.history = history; this.files = files; this.lang = plugin.lang();
    }

    boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission("xpeconomy.balance")) { lang.send(sender, "common.no-permission"); return true; }
        if (!(sender instanceof Player player)) { lang.send(sender, "common.players-only"); return true; }
        boolean export = args.length > 0 && args[0].equalsIgnoreCase("export");
        boolean summary = args.length > 0 && args[0].equalsIgnoreCase("summary");
        int offset = export || summary ? 1 : 0;
        Integer days = args.length == offset ? Integer.valueOf(7) : AccountStatement.parseDays(args[offset]);
        if (args.length > offset + 1 || days == null) { lang.send(player, "statement.usage"); return true; }
        if (history == null || !history.readable()) { lang.send(player, "history.unavailable"); return true; }
        AccountStatement statement = history.statement(player.getUniqueId(), days, System.currentTimeMillis());
        if (export) export(player, statement); else if (summary) summary(player, statement); else open(player, statement);
        return true;
    }

    private void summary(Player player, AccountStatement statement) {
        lang.send(player, "statement.summary-scope", "days", statement.days(), "max", history.capacity());
        var total = statement.total();
        lang.send(player, "statement.total", "count", total.count(), "incoming", total.incoming(), "outgoing", total.outgoing(), "net", total.net());
        statement.byCause().forEach((cause, t) -> lang.send(player, "statement.row",
                "cause", lang.component(player, XPVaultEconomy.historyCause(cause)), "count", t.count(),
                "incoming", t.incoming(), "outgoing", t.outgoing(), "net", t.net()));
    }

    private void open(Player player, AccountStatement statement) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(lang.component(player, "statement.scope", "max", history.capacity())));
        body.add(DialogBody.plainMessage(lang.component(player, "statement.period",
                "from", Instant.ofEpochMilli(statement.from()), "until", Instant.ofEpochMilli(statement.until()))));
        var total = statement.total();
        body.add(DialogBody.plainMessage(lang.component(player, "statement.total", "count", total.count(),
                "incoming", total.incoming(), "outgoing", total.outgoing(), "net", total.net())));
        statement.byCause().forEach((cause, t) -> body.add(DialogBody.plainMessage(lang.component(player, "statement.row",
                "cause", lang.component(player, XPVaultEconomy.historyCause(cause)), "count", t.count(),
                "incoming", t.incoming(), "outgoing", t.outgoing(), "net", t.net()))));
        if (statement.rows().isEmpty()) body.add(DialogBody.plainMessage(lang.component(player, "statement.empty")));
        List<DialogButton> buttons = new ArrayList<>();
        for (int days : List.of(1, 7, 30)) buttons.add(new DialogButton(lang.component(player, "statement.days", "days", days),
                (p, response) -> command(p, new String[] {String.valueOf(days)})));
        buttons.add(new DialogButton(lang.component(player, "statement.export"), (p, response) -> export(p, statement)));
        buttons.add(new DialogButton(lang.component(player, "statement.back"), (p, response) -> plugin.openAccount(p)));
        core.dialogs().open(plugin, player, new DialogRequest(lang.component(player, "statement.title"), body, List.of(), buttons,
                p -> p.isOnline() && plugin.isEnabled() && p.hasPermission("xpeconomy.balance"), () -> {},
                lang.plain(player, "statement.close"), 2));
    }

    private void export(Player player, AccountStatement statement) {
        if (!player.hasPermission("xpeconomy.balance")) { lang.send(player, "common.no-permission"); return; }
        if (!player.getUniqueId().equals(statement.account())) return;
        try {
            var file = statement.export(files, plugin.getDataFolder().toPath().resolve("exports"));
            lang.send(player, "statement.exported", "file", file.getFileName().toString());
        } catch (IOException failure) { lang.send(player, "history.unavailable"); }
    }
}
