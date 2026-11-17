package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import java.io.IOException;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * XP cheques: {@code /economy cheque <XP> [count]} turns XP into tradeable paper notes, right-click redeems one.
 * Only ids listed in the {@link ChequeLedger} are worth anything, so duplicated or edited items redeem nothing.
 */
final class Cheques implements Listener {
    static final int MAX_COUNT = 64;
    private final XPVaultEconomy economy;
    private final PlatformCompatInterface platform;
    private final ChequeLedger ledger;
    private final NamespacedKey idKey, amountKey;

    Cheques(XPVaultEconomy economy, PlatformCompatInterface platform, ChequeLedger ledger) {
        this.platform = platform;
        this.economy = economy;
        this.ledger = ledger;
        idKey = new NamespacedKey(economy, "cheque_id");
        amountKey = new NamespacedKey(economy, "cheque_amount");
    }

    /** Count argument: 1..64, null when invalid; a missing argument means one cheque. */
    static Integer parseCount(String[] args) {
        if (args.length < 2) return 1;
        if (!args[1].trim().matches("\\d{1,3}")) return null;
        int count = Integer.parseInt(args[1].trim());
        return count < 1 || count > MAX_COUNT ? null : count;
    }

    /** Total XP of {@code count} cheques of {@code amount}, or -1 on overflow beyond the balance limit. */
    static long total(long amount, int count, long maximum) {
        if (amount <= 0 || count <= 0 || amount > maximum / count) return -1;
        return amount * count;
    }

    boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { economy.lang().send(sender, "common.players-only"); return true; }
        if (args.length == 0 || args.length > 2) { economy.lang().send(player, "cheque.usage"); return true; }
        if (!ledger.readable()) { economy.lang().send(player, "cheque.unavailable"); return true; }
        Long amount = economy.parsePoints(args[0]);
        Integer count = parseCount(args);
        if (amount == null || amount <= 0 || count == null) { economy.lang().send(player, "cheque.usage"); return true; }
        long total = total(amount, count, economy.maxBalance());
        if (total < 0) { economy.lang().send(player, "cheque.usage"); return true; }
        if (economy.balance(player) < total) {
            economy.lang().send(player, "cheque.insufficient", "amount", economy.formatPoints(player, total));
            return true;
        }
        if (freeSlots(player) < count) { economy.lang().send(player, "cheque.no-space", "count", count); return true; }
        TransactionResult paid = economy.withdraw(player, total);
        if (!paid.success()) { economy.lang().send(player, "cheque.insufficient", "amount", economy.formatPoints(player, total)); return true; }
        List<ChequeLedger.Cheque> issued;
        try { issued = ledger.issue(player.getUniqueId(), amount, count, System.currentTimeMillis()); }
        catch (IOException failure) {
            economy.deposit(player, total);
            economy.changed();
            economy.getLogger().severe("Could not record XP cheques; XP returned to " + player.getName() + ": " + failure);
            economy.lang().send(player, "cheque.unavailable");
            return true;
        }
        for (ChequeLedger.Cheque cheque : issued) player.getInventory().addItem(item(cheque, player.getName(), player));
        economy.changed();
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1.2f);
        economy.lang().send(player, "cheque.issued", "count", count, "amount", economy.formatPoints(player, amount),
                "total", economy.formatPoints(player, total));
        return true;
    }

    private static int freeSlots(Player player) {
        int free = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) if (item == null || item.getType().isAir()) free++;
        return free;
    }

    ItemStack item(ChequeLedger.Cheque cheque, String issuerName, CommandSender viewer) {
        ItemStack item = new ItemStack(Material.PAPER);
        platform.editMeta(item, meta -> {
            platform.displayName(meta, Component.text(economy.lang().plain(viewer, "cheque.item-name", "amount",
                    economy.formatPoints(viewer, cheque.amount())), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            platform.lore(meta, List.of(
                    Component.text(economy.lang().plain(viewer, "cheque.item-issuer", "player", issuerName), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text(economy.lang().plain(viewer, "cheque.item-hint"), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
            meta.setEnchantmentGlintOverride(true);
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, cheque.id());
            meta.getPersistentDataContainer().set(amountKey, PersistentDataType.LONG, cheque.amount());
        });
        return item;
    }

    String chequeId(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    /**
     * Redeeming goes through Core's stages: ACT, the feature consumes the click. It also receives cancelled events,
     * because the server delivers a right click into the air as cancelled.
     */
    void handleEvents(EventStages stages) {
        stages.handle(economy, PlayerInteractEvent.class, EventStage.ACT, true, this::redeem);
    }

    /** Stage ACT, registered in {@link #handleEvents}. */
    public void redeem(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        String id = chequeId(hand);
        if (id == null) return;
        event.setCancelled(true);
        if (!player.hasPermission("xpeconomy.cheque")) { economy.lang().send(player, "common.no-permission"); return; }
        if (!ledger.readable()) { economy.lang().send(player, "cheque.unavailable"); return; }
        Optional<ChequeLedger.Cheque> cheque;
        try { cheque = ledger.redeem(id); }
        catch (IOException failure) { economy.getLogger().severe("Could not update XP cheques: " + failure); economy.lang().send(player, "cheque.unavailable"); return; }
        if (cheque.isEmpty()) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
            economy.lang().send(player, "cheque.invalid");
            return;
        }
        TransactionResult result = economy.deposit(player, cheque.get().amount());
        if (!result.success()) {
            try { ledger.restore(cheque.get()); }
            catch (IOException failure) { economy.getLogger().severe("Cheque " + id + " (" + cheque.get().amount() + " XP) could not be restored: " + failure); }
            economy.lang().send(player, "cheque.full");
            return;
        }
        hand.setAmount(hand.getAmount() - 1);
        player.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
        economy.changed();
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        String issuer = Optional.ofNullable(Bukkit.getOfflinePlayer(cheque.get().issuer()).getName()).orElse("?");
        economy.lang().send(player, "cheque.redeemed", "amount", economy.formatPoints(player, cheque.get().amount()), "player", issuer);
    }

    /** Cheques are paper; never let them be crafted into maps or books by accident. */
    @EventHandler public void craft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix())
            if (chequeId(item) != null) { event.getInventory().setResult(null); return; }
    }

    int outstanding() { return ledger.size(); }
    long outstandingTotal() { return ledger.total(); }
}
