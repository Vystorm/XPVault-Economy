package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class StatementCommandsTest {
    @TempDir Path dir;
    private XPVaultEconomy plugin;
    private API core;
    private Player player;
    private TransactionHistory history;
    private StatementCommands commands;

    @BeforeEach void setup() throws Exception {
        plugin = mock(XPVaultEconomy.class);
        core = mock(API.class, RETURNS_DEEP_STUBS);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.locale()).thenReturn(Locale.US);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission("xpeconomy.balance")).thenReturn(true);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.lang()).thenReturn(Lang.create("XPVaultEconomy", Map.of("en", Files.readString(Path.of("lang/en.yml")))));
        history = new TransactionHistory(dir.resolve("journal.json"), AtomicFilesInterface.DEFAULT);
        history.record(UUID.randomUUID(), player.getUniqueId(), null, "credit", 0, 100);
        commands = new StatementCommands(plugin, core, history, AtomicFilesInterface.DEFAULT);
    }

    @Test void commandExportsOnlyOwnRowsAndDefaultsToSevenDays() throws Exception {
        history.record(UUID.randomUUID(), UUID.randomUUID(), null, "credit", 0, 999);
        assertTrue(commands.command(player, new String[] {"EXPORT"}));
        String csv = Files.readString(dir.resolve("exports/" + player.getUniqueId() + "-statement-7d.csv"));
        assertTrue(csv.contains("credit,1,100,0,100"));
        assertFalse(csv.contains(",0,999,999"));
        verifyNoInteractions(core);
    }

    @Test void summaryReportsOnlyOwnRetainedBookingsWithoutOpeningDialogOrWritingFiles() {
        history.record(UUID.randomUUID(), UUID.randomUUID(), null, "credit", 0, 999);
        history.record(UUID.randomUUID(), player.getUniqueId(), null, "debit", 100, 70);
        commands.command(player, new String[]{"summary"});
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(message.capture());
        String text = message.getAllValues().stream().map(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()::serialize)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(text.contains("Last 7 days"));
        assertTrue(text.contains("2 bookings: incoming 100 XP, outgoing 30 XP, net 70 XP"));
        assertFalse(text.contains("999"));
        assertFalse(Files.exists(dir.resolve("exports")));
        verifyNoInteractions(core);
    }

    @Test void invalidOrUnauthorizedCallsCannotExport() {
        for (String[] args : List.of(new String[] {"export", "0"}, new String[] {"export", "7", "extra"},
                new String[] {"Steve"}, new String[] {"30", "extra"})) commands.command(player, args);
        when(player.hasPermission("xpeconomy.balance")).thenReturn(false);
        commands.command(player, new String[] {"export", "7"});
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission("xpeconomy.balance")).thenReturn(true);
        commands.command(console, new String[] {"export"});
        assertFalse(Files.exists(dir.resolve("exports")));
        verifyNoInteractions(core);
    }

    @Test void dialogExportsDisplayedSnapshotAndRechecksPermission() throws Exception {
        try (var bodies = mockStatic(DialogBody.class)) {
            DialogBody body = mock(DialogBody.class);
            bodies.when(() -> DialogBody.plainMessage(any(Component.class))).thenReturn(body);
            commands.command(player, new String[0]);
            ArgumentCaptor<DialogRequest> request = ArgumentCaptor.forClass(DialogRequest.class);
            verify(core.dialogs()).open(eq(plugin), eq(player), request.capture());
            history.record(UUID.randomUUID(), player.getUniqueId(), null, "credit", 100, 900);
            var export = request.getValue().buttons().get(3);
            when(player.hasPermission("xpeconomy.balance")).thenReturn(false);
            assertFalse(request.getValue().valid().test(player));
            export.action().accept(player, null);
            assertFalse(Files.exists(dir.resolve("exports")));
            when(player.hasPermission("xpeconomy.balance")).thenReturn(true);
            export.action().accept(player, null);
            String csv = Files.readString(dir.resolve("exports/" + player.getUniqueId() + "-statement-7d.csv"));
            assertTrue(csv.contains("credit,1,100,0,100"));
            assertFalse(csv.contains(",100,900,800"));
        }
    }

    @Test void unreadableJournalProducesNoExport() throws Exception {
        Path broken = dir.resolve("broken.json"); Files.writeString(broken, "broken");
        new StatementCommands(plugin, core, new TransactionHistory(broken, AtomicFilesInterface.DEFAULT), AtomicFilesInterface.DEFAULT).command(player, new String[] {"export"});
        assertFalse(Files.exists(dir.resolve("exports")));
        verifyNoInteractions(core);
    }
}
