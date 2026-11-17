package local.xpeconomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The handlers of shared events that Vystorm Core orders: activity (OBSERVE) and cheque redeeming (ACT). */
class SharedEventStagesTest {
    private final Plugin economy = EventStageTesting.plugin("XPVaultEconomy"), other = EventStageTesting.plugin("AProtectionPlugin");
    private final UUID id = UUID.randomUUID();

    private Player player() {
        Player player = mock(Player.class, RETURNS_DEEP_STUBS);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    @Test void givenInteraction_whenCalledByCoresStages_thenOnlyAnInteractionThatWasNotDeniedCountsAsActivity() {
        AtomicLong now = new AtomicLong(1000);
        RewardActivity activity = new RewardActivity(now::get, () -> 10_000L);
        boolean[] deny = {true};
        EventStageTesting stages = EventStageTesting.detached();
        activity.handleActivityEvents(economy, stages.stages());
        stages.handle(other, PlayerInteractEvent.class, EventStage.PROTECT, event -> event.setCancelled(deny[0]));
        Player player = player();
        Block block = mock(Block.class);

        stages.fire(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP));
        // a click into the air arrives cancelled (no block to use) and never counted
        stages.fire(new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, null, null, BlockFace.SELF));
        assertEquals(0, activity.eligible(id, 0, 5000), "a denied interaction and an air click are no activity");

        deny[0] = false;
        stages.fire(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP));
        now.set(3000);
        assertEquals(2000, activity.eligible(id, 1000, 3000));
    }

    @Test void givenInventoryClick_whenCalledByCoresStages_thenOnlyAClickThatWasNotDeniedCountsAsActivity() {
        AtomicLong now = new AtomicLong(1000);
        RewardActivity activity = new RewardActivity(now::get, () -> 10_000L);
        boolean[] deny = {true};
        EventStageTesting stages = EventStageTesting.detached();
        activity.handleActivityEvents(economy, stages.stages());
        stages.handle(other, InventoryClickEvent.class, EventStage.PROTECT, event -> event.setCancelled(deny[0]));
        Player player = player();
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(player);

        stages.fire(new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        assertEquals(0, activity.eligible(id, 0, 5000), "a denied click is no activity");

        deny[0] = false;
        stages.fire(new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        now.set(3000);
        assertEquals(2000, activity.eligible(id, 1000, 3000));
    }

    @Test void givenCheques_whenRegistered_thenRedeemingActsAndAlsoSeesClicksIntoTheAir(@TempDir Path dir) {
        XPVaultEconomy plugin = mock(XPVaultEconomy.class);
        when(plugin.getName()).thenReturn("XPVaultEconomy");
        when(plugin.namespace()).thenReturn("xpvaulteconomy");
        when(plugin.isEnabled()).thenReturn(true);
        Cheques cheques = new Cheques(plugin, mock(PlatformCompatInterface.class),
                new ChequeLedger(dir.resolve("data/cheques.json"), AtomicFilesInterface.DEFAULT));
        EventStageTesting stages = EventStageTesting.detached();
        cheques.handleEvents(stages.stages());

        List<EventStages.Registration> handlers = stages.handlers(PlayerInteractEvent.class);
        assertEquals(1, handlers.size());
        assertEquals(EventStage.ACT, handlers.get(0).stage());
        assertTrue(handlers.get(0).receivesCancelled());

        // a right click into the air is delivered cancelled and still reaches the handler, which looks at the hand
        Player inAir = player();
        PlayerInteractEvent air = stages.fire(new PlayerInteractEvent(inAir, Action.RIGHT_CLICK_AIR, null, null, BlockFace.SELF));
        verify(inAir.getInventory()).getItemInMainHand();
        assertFalse(air.useItemInHand() == org.bukkit.event.Event.Result.DENY, "an ordinary item is left alone");

        // a left click is not a redeem attempt
        Player left = player();
        stages.fire(new PlayerInteractEvent(left, Action.LEFT_CLICK_AIR, null, null, BlockFace.SELF));
        verify(left.getInventory(), never()).getItemInMainHand();
    }
}
