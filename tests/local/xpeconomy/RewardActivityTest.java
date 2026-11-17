package local.xpeconomy;
import static org.junit.jupiter.api.Assertions.*;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
class RewardActivityTest {
    @Test void givenAfkGap_whenActivityResumes_thenIdleTimeIsNotPaidBack() {
        AtomicLong now = new AtomicLong(1000); UUID id = UUID.randomUUID();
        RewardActivity activity = new RewardActivity(now::get, () -> 10_000L);
        activity.action(id); now.set(21_000);
        assertTrue(activity.afk(id)); assertEquals(10_000, activity.eligible(id, 1000, now.get()));
        activity.action(id); now.set(22_000);
        assertEquals(1000, activity.eligible(id, 11_000, now.get()));
    }
    @Test void givenBlockBreakAndPlace_whenCalledByCoresStages_thenOnlyAnActionThatWasNotDeniedCountsAsActivity() {
        AtomicLong now = new AtomicLong(1000); UUID id = UUID.randomUUID();
        RewardActivity activity = new RewardActivity(now::get, () -> 10_000L);
        org.bukkit.plugin.Plugin economy = EventStageTesting.plugin("XPVaultEconomy"), other = EventStageTesting.plugin("AProtectionPlugin");
        boolean[] deny = {true};
        EventStageTesting harness = EventStageTesting.detached();
        {
            Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages stages = harness.stages();
            activity.handleBlockEvents(economy, stages);
            stages.handle(other, org.bukkit.event.block.BlockBreakEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.PROTECT, event -> event.setCancelled(deny[0]));
            stages.handle(other, org.bukkit.event.block.BlockPlaceEvent.class, Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage.PROTECT, event -> event.setCancelled(deny[0]));
        }
        org.bukkit.entity.Player player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        org.mockito.Mockito.when(player.getUniqueId()).thenReturn(id);
        org.bukkit.block.Block block = org.mockito.Mockito.mock(org.bukkit.block.Block.class);
        java.util.function.Supplier<org.bukkit.event.block.BlockPlaceEvent> placing = () -> new org.bukkit.event.block.BlockPlaceEvent(block,
                org.mockito.Mockito.mock(org.bukkit.block.BlockState.class), block, org.mockito.Mockito.mock(org.bukkit.inventory.ItemStack.class),
                player, true, org.bukkit.inventory.EquipmentSlot.HAND);
        harness.fire(new org.bukkit.event.block.BlockBreakEvent(block, player));
        harness.fire(placing.get());
        assertEquals(0, activity.eligible(id, 0, 5000), "denied actions are no activity");
        deny[0] = false;
        harness.fire(new org.bukkit.event.block.BlockBreakEvent(block, player));
        now.set(3000);
        assertEquals(2000, activity.eligible(id, 1000, 3000));
        now.set(20_000);
        assertTrue(activity.afk(id));
        harness.fire(placing.get());
        assertFalse(activity.afk(id));
    }
    @Test void givenDisabledPolicy_whenIdle_thenExistingAccrualIsPreserved() {
        assertEquals(50_000, new RewardActivity(() -> 0, () -> 0).eligible(UUID.randomUUID(), 1, 50_001));
    }
}
