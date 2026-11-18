package local.xpeconomy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.compat.dialog.DialogBody;
import Vystorm.vystorm_core.compat.dialog.DialogRequest;
import Vystorm.vystorm_core.lang.Lang;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The /pay confirm dialog stays as opened: a balance change (online bonus) must not swallow the Confirm click. */
class AccountMenuConfirmTest {
    private XPVaultEconomy plugin;
    private API core;
    private Player player;
    private OfflinePlayer target;
    private AccountMenu menu;

    @BeforeEach void setup() throws Exception {
        plugin = mock(XPVaultEconomy.class);
        core = mock(API.class, RETURNS_DEEP_STUBS);
        player = mock(Player.class);
        target = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.locale()).thenReturn(Locale.US);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.lang()).thenReturn(Lang.create("XPVaultEconomy", Map.of("en", Files.readString(Path.of("lang/en.yml")))));
        when(plugin.displayName(target)).thenReturn("Esoren");
        when(plugin.formatPoints(any(org.bukkit.command.CommandSender.class), anyLong())).thenAnswer(a -> a.getArgument(1) + " XP");
        when(plugin.errorText(any(), anyString())).thenReturn("Not enough XP points");
        when(plugin.onlineRewardStatus(any(), any())).thenReturn(Component.text("bonus"));
        menu = new AccountMenu(plugin, core);
    }

    private DialogRequest openConfirm(long balanceAtOpen) {
        when(plugin.balance(player)).thenReturn(balanceAtOpen);
        menu.confirmTransfer(player, target, 10);
        ArgumentCaptor<DialogRequest> request = ArgumentCaptor.forClass(DialogRequest.class);
        verify(core.dialogs(), atLeastOnce()).open(eq(plugin), eq(player), request.capture());
        clearInvocations(core.dialogs());
        return request.getValue();
    }

    @Test void confirmPaysAfterTheOnlineBonusRaisedTheBalance() {
        try (var bodies = mockStatic(DialogBody.class)) {
            bodies.when(() -> DialogBody.plainMessage(any())).thenReturn(mock(DialogBody.class));
            DialogRequest confirm = openConfirm(100);
            when(plugin.balance(player)).thenReturn(103L); // bonus paid while the dialog was open
            confirm.buttons().get(0).action().accept(player, null);
            verify(plugin).transfer(player, target, 10);
        }
    }

    @Test void fivePaymentsInARowGoThroughWhileTheBalanceKeepsChanging() {
        try (var bodies = mockStatic(DialogBody.class)) {
            bodies.when(() -> DialogBody.plainMessage(any())).thenReturn(mock(DialogBody.class));
            for (int i = 0; i < 5; i++) {
                DialogRequest confirm = openConfirm(100 + i);
                when(plugin.balance(player)).thenReturn(101L + i);
                confirm.buttons().get(0).action().accept(player, null);
            }
            verify(plugin, times(5)).transfer(player, target, 10);
        }
    }

    @Test void confirmRefusesClearlyWhenTheBalanceNoLongerSuffices() {
        try (var bodies = mockStatic(DialogBody.class)) {
            ArgumentCaptor<Component> texts = ArgumentCaptor.forClass(Component.class);
            bodies.when(() -> DialogBody.plainMessage(texts.capture())).thenReturn(mock(DialogBody.class));
            DialogRequest confirm = openConfirm(100);
            when(plugin.balance(player)).thenReturn(5L);
            confirm.buttons().get(0).action().accept(player, null);
            verify(plugin, never()).transfer(any(), any(), anyLong());
            verify(core.dialogs()).open(eq(plugin), eq(player), any(DialogRequest.class));
            String shown = texts.getAllValues().stream()
                    .map(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()::serialize)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(shown.contains("Not enough XP points"), shown);
        }
    }
}
