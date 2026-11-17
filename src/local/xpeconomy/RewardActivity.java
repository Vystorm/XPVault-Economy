package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import java.util.*;
import java.util.function.LongSupplier;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

/** Main-thread activity windows. The grace is an AFK threshold, independent of reconnect grace. */
final class RewardActivity implements Listener {
    private record Window(long lastAction, long resumedAt) {}
    private final Map<UUID, Window> players = new HashMap<>();
    private final LongSupplier clock, grace;
    RewardActivity(LongSupplier clock, LongSupplier grace) { this.clock = clock; this.grace = grace; }
    void action(UUID id) {
        long now = clock.getAsLong(), timeout = grace.getAsLong();
        Window before = players.get(id);
        long resumed = before == null || (timeout > 0 && now - before.lastAction > timeout) ? now : before.resumedAt;
        players.put(id, new Window(now, resumed));
    }
    long eligible(UUID id, long from, long to) {
        long timeout = grace.getAsLong();
        if (timeout <= 0) return Math.max(0, to - from);
        Window w = players.get(id);
        if (w == null) return 0;
        return Math.max(0, Math.min(to, w.lastAction + timeout) - Math.max(from, w.resumedAt));
    }
    boolean afk(UUID id) {
        Window w = players.get(id); long timeout = grace.getAsLong();
        return timeout > 0 && w != null && clock.getAsLong() - w.lastAction >= timeout;
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (e.getTo() != null && !e.getFrom().equals(e.getTo())) action(e.getPlayer().getUniqueId());
    }
    /**
     * Interactions and inventory clicks go through Core's stages like block break and place. Both only note that the
     * player is active: OBSERVE, never a cancelled event (so a click into the air, which the server delivers as
     * cancelled, does not count, as before).
     */
    void handleActivityEvents(Plugin owner, EventStages stages) {
        stages.handle(owner, PlayerInteractEvent.class, EventStage.OBSERVE, this::interact);
        stages.handle(owner, InventoryClickEvent.class, EventStage.OBSERVE, this::inventory);
    }
    /** Stage OBSERVE, registered in {@link #handleActivityEvents}. */
    public void interact(PlayerInteractEvent e) { action(e.getPlayer().getUniqueId()); }
    /** Stage OBSERVE, registered in {@link #handleActivityEvents}. */
    public void inventory(InventoryClickEvent e) { action(e.getWhoClicked().getUniqueId()); }
    /**
     * Block break and place go through Core's stages, so Core decides when they run among all Vystorm plugins. Both
     * only note that the player is active: OBSERVE, the final outcome, never a cancelled event.
     */
    void handleBlockEvents(Plugin owner, EventStages stages) {
        stages.handle(owner, BlockBreakEvent.class, EventStage.OBSERVE, this::breakBlock);
        stages.handle(owner, BlockPlaceEvent.class, EventStage.OBSERVE, this::place);
    }
    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void breakBlock(BlockBreakEvent e) { action(e.getPlayer().getUniqueId()); }
    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void place(BlockPlaceEvent e) { action(e.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent e) { action(e.getPlayer().getUniqueId()); }
    @EventHandler public void quit(PlayerQuitEvent e) { players.remove(e.getPlayer().getUniqueId()); }
}
