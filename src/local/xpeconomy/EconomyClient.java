package local.xpeconomy;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._uiMethods.settings.SettingsSection;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.schedule.TaskGroup;
import Vystorm.vystorm_core.web.ClientElement;
import Vystorm.vystorm_core.web.ClientServices;
import Vystorm.vystorm_core.web.ClientUi;
import Vystorm.vystorm_core.web.VystormClientReadyEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Native displays of the Vystorm client mod (Core {@code web-client-ui}): an XP-account HUD box (balance, level and the
 * online-bonus countdown) and toasts for online-bonus payouts and received payments. Server thread only. Players without the mod, with the HUD switched off or with
 * native elements hidden (key H) keep the chat and action-bar messages.
 *
 * <p>With Core 0.26.4 and client 0.9.0 the online-bonus countdown is a bar below the box that the client counts down
 * itself ({@code Bar.countdown} + {@code {time}} label), re-sent only when the bonus changes instead of every second,
 * and both stack with other plugins' HUD elements at the top right. Older clients get the box with the seconds line.
 */
final class EconomyClient implements Listener {
    static final String HUD = "account";
    static final String PREF_HUD = "client-hud";
    static final String SECTION = "client";
    static final String BONUS = "bonus";
    /** The client counts down itself; re-send only when the end moves by more than this (rounding to whole seconds). */
    static final long BONUS_DRIFT_MS = 2_000L;

    private final XPVaultEconomy plugin;
    private final API core;
    private final Lang lang;
    private final ClientUi ui;
    /** Last sent HUD JSON per player (nothing is sent while it is unchanged). */
    private final Map<UUID, String> shown = new HashMap<>();
    /** Countdown bar as last sent per player: label and expected end. */
    private final Map<UUID, Bonus> bonus = new HashMap<>();

    record Bonus(String label, long end) {}

    EconomyClient(XPVaultEconomy plugin, API core, Lang lang) {
        this.core = core;
        this.plugin = plugin;
        this.lang = lang;
        this.ui = core.web().clientUi().of(plugin);
    }

    /** Settings key of the personal HUD section (for {@code Ui.Button.openSettings}). */
    String settingsKey() {
        return plugin.getName().toLowerCase(java.util.Locale.ROOT) + ":" + SECTION;
    }

    /** This player's client draws the 0.9.0 elements (countdown label placeholders). */
    private boolean countdown(Player player) {
        return (core.web().client().capabilities(player) & ClientServices.CAP_UI_EXTENSIONS) != 0;
    }

    void start(TaskGroup timers) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        timers.timer(20L, 20L, this::tick);
    }

    /** Personal on/off switch for the HUD box (stored in the player's data through Core {@code PlayerPrefs}). */
    SettingsSection settingsSection() {
        return SettingsSection.player(SECTION, lang.ref("client.settings-title"), lang.ref("client.settings-description"),
                List.of(core.settings().prefs().toggle(plugin, PREF_HUD, lang.ref("client.hud"), lang.ref("client.hud-description"), true)));
    }

    // ---- HUD ----

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) update(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReady(VystormClientReadyEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
        bonus.remove(event.getPlayer().getUniqueId());
        update(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        shown.remove(event.getPlayer().getUniqueId());
        bonus.remove(event.getPlayer().getUniqueId());
    }

    void update(Player player) {
        UUID id = player.getUniqueId();
        if (!wantsHud(player)) {
            if (shown.remove(id) != null) ui.remove(player, HUD);
            if (bonus.remove(id) != null) ui.remove(player, BONUS);
            return;
        }
        RewardView reward = plugin.rewardView(id, true);
        String language = lang.language(player);
        boolean bar = countdown(player) && "active".equals(reward.state()) && updateBonus(player, reward, language);
        if (!bar && bonus.remove(id) != null) ui.remove(player, BONUS);
        ClientElement.Panel element = hud(player, reward, language, bar);
        String json = element.toJson().toString();
        if (json.equals(shown.get(id))) return;
        boolean sent = shown.containsKey(id) && ui.has(player, HUD) ? ui.patch(player, element) : ui.put(player, element);
        if (sent) shown.put(id, json);
        else shown.remove(id);
    }

    /** Countdown bar for an active streak; false if the client refused it (then the box keeps the seconds line). */
    private boolean updateBonus(Player player, RewardView reward, String language) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        long remaining = reward.remainingSeconds() * 1000L;
        Bonus wanted = new Bonus(lang.plain(language, "client.hud-bonus",
                "next_xp", XPVaultEconomy.formatReward(language, reward.nextXp()), "time", ClientElement.Bar.TIME), now + remaining);
        Bonus last = bonus.get(id);
        if (last != null && last.label().equals(wanted.label()) && Math.abs(last.end() - wanted.end()) <= BONUS_DRIFT_MS
                && ui.has(player, BONUS)) return true;
        if (!ui.put(player, bonusBar(wanted.label(), remaining, reward.intervalSeconds() * 1000L))) return false;
        bonus.put(id, wanted);
        return true;
    }

    /** Below the account box (stacked), counted down by the client: value/max in seconds, label with {@code {time}}. */
    static ClientElement.Bar bonusBar(String label, long remainingMs, long totalMs) {
        return ClientElement.bar(BONUS).anchor(ClientElement.Anchor.TOP_RIGHT).offset(4, 52).order(11).stack(true)
                .width(120).countdown(remainingMs, Math.max(remainingMs, totalMs)).color("#55FFFF").label(label);
    }

    private boolean wantsHud(Player player) {
        return player.isOnline() && player.hasPermission("xpeconomy.balance") && ui.available(player, ClientServices.CAP_HUD)
                && core.settings().prefs().bool(plugin, player, PREF_HUD, true);
    }

    private ClientElement.Panel hud(Player player, RewardView reward, String language, boolean bar) {
        long balance = plugin.balance(player);
        String rewardLine = switch (reward.state()) {
            case "active" -> bar ? null : lang.plain(language, "client.hud-reward-active",
                    "next_xp", XPVaultEconomy.formatReward(language, reward.nextXp()), "seconds", reward.remainingSeconds());
            case "afk" -> lang.plain(language, "reward.status-afk", "streak", reward.streak());
            case "disabled" -> null;
            default -> lang.plain(language, "client.hud-reward-none");
        };
        return hud(lang.plain(language, "client.hud-title"),
                lang.plain(language, "client.hud-balance", "amount", plugin.formatPoints(language, balance)),
                lang.plain(language, "client.hud-level", "level", BalanceRanking.levelForPoints(balance)), rewardLine, true);
    }

    /**
     * The HUD box (top right, below the vanilla status effects); {@code rewardLine} may be null. With {@code stack}
     * (Core 0.26.4) it moves down instead of covering other plugins' elements at the top right.
     */
    static ClientElement.Panel hud(String title, String balance, String level, String rewardLine, boolean stack) {
        ClientElement.Panel panel = ClientElement.panel(HUD).anchor(ClientElement.Anchor.TOP_RIGHT).offset(4, 52).order(10)
                .icon(ClientElement.Icon.item("minecraft:experience_bottle"))
                .title(ClientElement.Text.of(title, "gold"))
                .line(ClientElement.Text.of(balance, "green").bold())
                .line(ClientElement.Text.of(level, "gray"));
        if (rewardLine != null) panel.line(ClientElement.Text.of(rewardLine, "aqua"));
        if (stack) panel.stack(true);
        return panel;
    }

    // ---- Toasts ----

    /** Whether notable events go to a toast instead of chat/action bar for this player. */
    boolean toasts(Player player) {
        if (!ui.available(player, ClientServices.CAP_TOASTS)) return false;
        return ui.visible(player);
    }

    /** Online-bonus payout as a toast; {@code false} = show the vanilla message. */
    boolean rewardToast(Player player, long paid, long streak, String nextXp) {
        if (!toasts(player)) return false;
        String language = lang.language(player);
        return ui.toast(player, rewardToast(lang.plain(language, "client.reward-title", "xp", paid),
                lang.plain(language, "client.reward-text", "streak", streak, "next_xp", nextXp)));
    }

    static ClientElement.Toast rewardToast(String title, String text) {
        return ClientElement.toast(ClientElement.Text.of(title, "green")).text(text)
                .icon(ClientElement.Icon.item("minecraft:experience_bottle")).style(ClientElement.Toast.Style.SUCCESS).duration(4000);
    }

    /** Received payment as a toast (the chat line with its reply button is sent as well). */
    boolean paymentToast(Player target, String payer, String amount) {
        if (!toasts(target)) return false;
        return ui.toast(target, paymentToast(lang.plain(target, "client.payment-title", "amount", amount),
                lang.plain(target, "client.payment-text", "player", payer)));
    }

    static ClientElement.Toast paymentToast(String title, String text) {
        return ClientElement.toast(ClientElement.Text.of(title, "gold")).text(text)
                .icon(ClientElement.Icon.item("minecraft:emerald")).style(ClientElement.Toast.Style.INFO).duration(6000);
    }
}
