package local.xpeconomy;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.lang.Lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import org.bukkit.command.CommandSender;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.NumberFormat;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class XPVaultEconomy extends JavaPlugin implements Listener {
    private API core;
    private BalancePrivacy privacy;
    private PaymentDesk paymentDesk;
    private TransactionHistory transactionHistory;
    private AwayIncome awayIncome;
    private Cheques cheques;
    /** Shared XP banks (/economy bank); null until the accounts are loaded. */
    private Banks banks;
    /** Recurring payments (/economy standing); null until the accounts are loaded. */
    private StandingOrders standingOrders;
    /** Term deposits (/economy term); null until the accounts are loaded. */
    private TermDeposits termDeposits;
    /** Donation goals (/economy fund); null until the accounts are loaded. */
    private Funds funds;
    private final ThreadLocal<Boolean> paymentJournal = ThreadLocal.withInitial(() -> false);
    private PaymentCommands paymentCommands;
    private RewardActivity rewardActivity;
    private long afkTimeoutMillis;
    private Lang lang;
    private String languageSetting = "auto";
    private final Map<UUID, StoredAccount> accounts = new ConcurrentHashMap<>();
    private final Map<String, UUID> names = new ConcurrentHashMap<>();
    private final Map<UUID, OnlineRewardState> onlineRewards = new ConcurrentHashMap<>();
    private final Object transactionLock = new Object();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicBoolean flushQueued = new AtomicBoolean();

    private BalanceStore store;
    private OnlineRewardStore onlineRewardStore;
    /** True once accounts are loaded and the Vault providers are registered (mysql: after Core's shared database). */
    private volatile boolean ready;
    private ExecutorService storageExecutor;
    private XPEconomyProvider economy;
    private volatile long savedRevision;
    private long failedFlushes;
    private long maximumBalance;
    private long startingBalance;
    private String currencySingular;
    private String currencyPlural;
    private boolean onlineRewardsEnabled;
    private double onlineRewardBaseXp;
    private double onlineRewardGrowthMultiplier;
    private long onlineRewardIntervalMillis;
    private long onlineRewardGraceMillis;
    private long onlineRewardCheckIntervalTicks;
    private boolean onlineRewardNotificationsEnabled;
    private boolean onlineRewardNotificationsActionBar;
    private boolean showOnlineRewardStatusInBalance;
    private String onlineRewardMessage;
    private EconomySettings settings;
    /** Sync, save and web-push timers; closed on disable. Created after the Core check (Core TaskGroup). */
    private Vystorm.vystorm_core.schedule.TaskGroup timers;
    /** Online-bonus timer; cancelled and rescheduled when its settings change. */
    private Vystorm.vystorm_core.schedule.TaskGroup rewardTimer;
    private AccountMenu accountMenu;
    /** Native account panel (Core {@code panels}); null if it could not be registered. */
    private AccountPanel accountPanel;
    /** HUD and toasts of the Vystorm client mod (Core {@code web-client-ui}); null if it could not be started. */
    private EconomyClient client;
    private LeaderboardCache leaderboard;
    private int topPageSize;
    private long topCacheMillis;
    /** Optional web page (Core 0.24.0+ with web); null without it. */
    private EconomyWeb web;
    /** Accounts whose web view changed (balance, online state, bonus streak); pushed once per second. */
    private final java.util.Set<UUID> webDirty = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        if (!CoreApi.atLeast("0.64.0")) {
            getLogger().severe("Vystorm Core 0.64.0 or newer is required; please update Core first.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Bind before loading accounts or registering either Vault provider.
        core = CoreApi.api().orElseThrow();
        core.admin().updates().spigot(this, 139852);
        DebugMessages.mid("[XPVaultEconomy 90] Vystorm Core API connected");
        saveDefaultConfig();
        transactionHistory = new TransactionHistory(getDataFolder().toPath().resolve("data/transaction-history.json"), core.data().files());
        awayIncome = new AwayIncome(getDataFolder().toPath().resolve("data/away-income.json"), core.data().files());
        paymentDesk = new PaymentDesk(getDataFolder().toPath().resolve("data/payment-requests.json"), java.time.Clock.systemUTC(), core.data().files());
        privacy = new BalancePrivacy(getDataFolder().toPath().resolve("data/player-privacy.json"), core.data().files());
        if (!privacy.readable()) getLogger().warning("Player privacy storage is unreadable; public balance visibility is disabled.");
        lang = core.lang().register(this);
        paymentCommands = new PaymentCommands(this, core, paymentDesk);
        applyLanguageSetting();
        registerCommands();
        core.registerTutorial(this);
        maximumBalance = Math.max(1L, Math.min(Integer.MAX_VALUE,
                getConfig().getLong("economy.maximum-balance", 2_000_000_000L)));
        applySettings(EconomySettings.read(getConfig(), maximumBalance));

        String storageType = getConfig().getString("storage.type", "yaml").trim().toLowerCase(Locale.ROOT);
        try {
            onlineRewardStore = new OnlineRewardStore(OnlineRewardStore.open(this, core));
            onlineRewards.putAll(onlineRewardStore.load());
            if (storageType.equals("yaml")) {
                BalanceStore local = new LocalBalanceStore(LocalBalanceStore.open(this, core));
                startWithStore(local, local.load());
            } else if (storageType.equals("mysql")) {
                openSharedDatabase();
            } else {
                throw new IllegalArgumentException("Unknown storage.type: " + storageType + " (allowed: yaml, mysql)");
            }
        } catch (Exception exception) {
            failStorage(exception);
        }
    }

    /**
     * The account menu in {@code /vmenu}: a native panel for players with the Vystorm client mod (Core 0.25.1+) with the
     * dialog as fallback for everyone else.
     */
    private void registerAccountMenu() {
        try {
            AccountPanel panel = new AccountPanel(this, core.gui().panels(), accountBackend(), lang);
            panel.register(accountMenu);
            accountPanel = panel;
            return;
        } catch (RuntimeException failure) {
            DebugMessages.high("[XPVaultEconomy/Client] Native account panel not available, using the dialog", failure);
        }
        accountMenu.register();
    }

    /** HUD box and toasts of the Vystorm client mod (Core 0.20.0+); without it everything stays chat/action bar. */
    private void startClient() {
        try {
            EconomyClient started = new EconomyClient(this, core, lang);
            started.start(timers);
            core.settings().register(this, started.settingsSection());
            client = started;
        } catch (RuntimeException failure) {
            client = null;
            DebugMessages.high("[XPVaultEconomy/Client] Client HUD not available; chat and action bar stay", failure);
        }
    }

    /** Opens the XP account: native panel with the mod, else the dialog (also the fallback inside the panel). */
    void openAccount(Player player) {
        if (accountPanel != null) accountPanel.open(player);
        else accountMenu.open(player);
    }

    /** Player menu "Pay": the /pay transfer dialog with the recipient filled in. */
    void openTransferTo(Player player, String target) {
        accountMenu.openTransferTo(player, target);
    }

    /** Player menu "Request XP": amount and purpose dialog that runs /economy request. */
    void openRequestTo(Player player, String target) {
        paymentCommands.openRequest(player, target);
    }

    /** Player menu admin entry: the same output as /economy admin history &lt;player&gt;. */
    void showAdminHistory(Player viewer, String target) {
        if (!viewer.hasPermission("xpeconomy.admin")) { lang.send(viewer, "common.no-permission"); return; }
        adminHistory(viewer, new String[] {"history", target});
    }

    /** The economy as seen by the native account panel. */
    private AccountPanel.Account accountBackend() {
        XPVaultEconomy economy = this;
        return new AccountPanel.Account() {
            public long balance(Player player) { return economy.balance(player); }
            public long maxBalance() { return economy.maxBalance(); }
            public String formatPoints(String language, long points) { return economy.formatPoints(language, points); }
            public RewardView rewardView(UUID player, boolean online) { return economy.rewardView(player, online); }
            public int topPageSize() { return economy.topPageSize(); }
            public void requestLeaderboard(Consumer<BalanceRanking> callback) { economy.requestLeaderboard(callback); }
            public boolean webAvailable() { return economy.webAvailable(); }
            public void openWeb(Player player, String subPath) { economy.openWeb(player, subPath); }
            public void openServerSettings(Player player) { core.settings().openSection(player, AccountMenu.SETTINGS_KEY); }
            public OfflinePlayer resolveKnownPlayer(String name) { return economy.resolveKnownPlayer(name); }
            public Component checkTransfer(Player payer, OfflinePlayer target, Long amount) { return economy.checkTransfer(payer, target, amount); }
            public Component transfer(Player payer, OfflinePlayer target, long amount) { return economy.transfer(payer, target, amount); }
            public String errorText(String language, String key) { return economy.errorText(language, key); }
            public String displayName(OfflinePlayer player) { return economy.displayName(player); }
            public PersonalEconomyPrefs.Goal goal(Player player) { return PersonalEconomyPrefs.goal(core.settings().prefs(), economy, player); }
            public List<PersonalEconomyPrefs.Payee> favorites(Player player) { return economy.favorites(player); }
            public OfflinePlayer player(UUID uuid) { return economy.player(uuid); }
            public String hudSettingsKey() { return client == null ? null : client.settingsKey(); }
            public boolean featureEnabled(AccountPanel.More feature) {
                return switch (feature) {
                    case BANKS -> banksEnabled();
                    case FUNDS -> fundsEnabled();
                    case STANDING -> standingOrdersEnabled();
                    case TERM -> termDepositsEnabled();
                    case TRANSFER, REQUESTS, STATEMENT, LEADERBOARD -> true;
                };
            }
            public void openFeature(Player player, AccountPanel.More feature) {
                switch (feature) {
                    case TRANSFER -> accountMenu.openTransferTo(player, "");
                    case REQUESTS -> paymentCommands.command(player, "requests", new String[0]);
                    case BANKS -> openBanks(player);
                    case FUNDS -> openFunds(player);
                    case STANDING -> openStandingOrders(player);
                    case TERM -> openTermDeposits(player);
                    case STATEMENT -> openStatement(player);
                    case LEADERBOARD -> accountMenu.openLeaderboard(player);
                }
            }
        };
    }

    private void failStorage(Exception exception) {
        DebugMessages.high("[XPVaultEconomy/Storage] Storage could not be started: " + storageFailure(exception));
        DebugMessages.high("[XPVaultEconomy/Storage] Disabling the plugin so that no XP balances are lost or diverge.");
        if (isEnabled()) Bukkit.getPluginManager().disablePlugin(this);
    }

    /**
     * {@code storage.type: mysql}: waits for Vystorm Core's shared database, migrates the schema and loads all
     * accounts off the server thread, then finishes the start on the server thread. Until then no Vault provider is
     * registered, so no plugin can read or change a balance that is not loaded yet. Without the database the plugin
     * disables itself (no fallback to the local file, so balances never diverge between servers).
     */
    private void openSharedDatabase() {
        String prefix = getConfig().getString("storage.table-prefix", "xpeco").trim();
        String table = core.database().sharedTableName(prefix, "balances"); // validates the prefix
        DebugMessages.mid("[XPVaultEconomy/Storage] storage.type=mysql: waiting for Vystorm Core's shared database …");
        core.database().sharedReady().thenAcceptAsync(database -> {
            try {
                if (database.isEmpty())
                    throw new IllegalStateException("Vystorm Core's shared database is not available "
                            + "(database.shared in plugins/vystorm_core/config.yml disabled or connection failed)");
                SharedBalanceStore shared = SharedBalanceStore.open(database.get(), prefix, table);
                Map<UUID, StoredAccount> loaded = shared.load();
                Bukkit.getScheduler().runTask(this, () -> {
                    if (isEnabled()) startWithStore(shared, loaded);
                });
            } catch (Exception exception) {
                if (isEnabled()) Bukkit.getScheduler().runTask(this, () -> failStorage(exception));
            }
        }, task -> {
            if (isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(this, task);
        });
    }

    /** Second half of the start (server thread): accounts are loaded, now offer them through Vault. */
    private void startWithStore(BalanceStore balanceStore, Map<UUID, StoredAccount> loaded) {
        store = balanceStore;
        for (StoredAccount account : loaded.values()) putLoadedAccount(account);

        storageExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "XPVaultEconomy-Storage");
            thread.setDaemon(true);
            return thread;
        });
        leaderboard = new LeaderboardCache(storageExecutor, task -> Bukkit.getScheduler().runTask(this, task),
                System::currentTimeMillis, failure -> DebugMessages.high(
                        "[XPVaultEconomy LeaderboardCache] Leaderboard could not be calculated", failure));

        economy = new XPEconomyProvider(this);
        Bukkit.getServicesManager().register(Economy.class, economy, this, ServicePriority.Highest);
        Bukkit.getServicesManager().register(net.milkbowl.vault2.economy.Economy.class,
                new XPEconomyProviderV2(this), this, ServicePriority.Highest);
        Bukkit.getPluginManager().registerEvents(this, this);
        rewardActivity = new RewardActivity(System::currentTimeMillis, this::afkGraceMillis);
        Bukkit.getPluginManager().registerEvents(rewardActivity, this);
        rewardActivity.handleBlockEvents(this, core.priority().stages());
        rewardActivity.handleActivityEvents(this, core.priority().stages());
        // reads the final outcome only; as before it also runs for a death that another plugin cancelled
        core.priority().stages().handle(this, PlayerDeathEvent.class, EventStage.OBSERVE, true, this::onDeath);
        cheques = new Cheques(this, core.compat().platform(), new ChequeLedger(getDataFolder().toPath().resolve("data/cheques.json"), core.data().files()));
        Bukkit.getPluginManager().registerEvents(cheques, this);
        cheques.handleEvents(core.priority().stages());
        banks = new Banks(this, core, new BankLedger(getDataFolder().toPath().resolve("data/banks.json"), core.data().files()));
        if (!banks.ledger().readable()) getLogger().severe("data/banks.json is unreadable; XP banks are locked until an admin repairs it.");
        standingOrders = new StandingOrders(this, core, new StandingOrderBook(getDataFolder().toPath().resolve("data/standing-orders.json"), core.data().files()));
        if (!standingOrders.book().readable()) getLogger().severe("data/standing-orders.json is unreadable; standing orders are paused until an admin repairs it.");
        termDeposits = new TermDeposits(this, core, new TermDepositBook(getDataFolder().toPath().resolve("data/term-deposits.json"), core.data().files()), termWallet());
        if (!termDeposits.book().readable()) getLogger().severe("data/term-deposits.json is unreadable; term deposits are locked until an admin repairs it.");
        funds = new Funds(this, core, new FundBook(getDataFolder().toPath().resolve("data/funds.json"), core.data().files()), fundWallet());
        if (!funds.book().readable()) getLogger().severe("data/funds.json is unreadable; donation goals are locked until an admin repairs it.");
        replayPendingBookings();
        accountMenu = new AccountMenu(this, core);
        registerAccountMenu();
        registerServerSettings();
        try {
            EconomyConfigEditor.register(core.settings().config(), this, lang, this::reloadSettings);
        } catch (RuntimeException failure) {
            DebugMessages.high("[XPVaultEconomy] Config editor registration failed", failure);
        }
        core.settings().register(this, Vystorm.vystorm_core._uiMethods.settings.SettingsSection.player("privacy", lang.ref("privacy.title"), lang.ref("privacy.description"),
                List.of(Vystorm.vystorm_core._uiMethods.settings.Setting.toggle("hidden", lang.ref("privacy.hidden"), "",
                    p -> privacy.hidden(p.getUniqueId()), (p, hidden) -> setPrivacy(p, hidden)))));
        core.admin().register(this, adminModule(getName(), lang));
        EconomyMenuEntries.register(core.gui().playerMenu(), this, lang);
        startWeb();

        long syncInterval = Math.max(1L, getConfig().getLong("sync.online-interval-ticks", 20L));
        long saveInterval = Math.max(20L, getConfig().getLong("sync.save-interval-ticks", 100L));
        timers = core.tasks().of(this, "timers");
        rewardTimer = core.tasks().of(this, "online-rewards");
        timers.timer(syncInterval, syncInterval, this::syncOnlinePlayers);
        timers.timer(saveInterval, saveInterval, this::queueFlush);
        if (web != null) timers.timer(20L, 20L, this::pushWebChanges);
        timers.timer(200L, 600L, () -> { standingOrders.runDue(); termDeposits.runDue(); funds.runDue(); queueFlush(); });
        if (onlineRewardsEnabled) scheduleOnlineRewards();
        startClient();

        ready = true;
        for (Player player : Bukkit.getOnlinePlayers()) handleJoin(player);
        DebugMessages.mid("[XPVaultEconomy 135] Vault economy registered: 1 unit = 1 real XP point; storage: "
                + store.description() + "; accounts loaded: " + accounts.size());
        if (onlineRewardsEnabled)
            DebugMessages.mid("[XPVaultEconomy 138] Online bonus active: " + onlineRewardBaseXp + " XP per "
                    + (onlineRewardIntervalMillis / 1000L) + " seconds, factor "
                    + onlineRewardGrowthMultiplier + ", rejoin grace "
                    + (onlineRewardGraceMillis / 60_000L) + " minutes; streaks loaded: " + onlineRewards.size());
    }

    @Override
    public void onDisable() {
        if (timers != null) timers.close();
        if (rewardTimer != null) rewardTimer.close();
        if (web != null) {
            web.unregister();
            web = null;
        }
        if (core != null) { core.gui().unregisterMenus(this); core.dialogs().removeAll(this); }
        Bukkit.getServicesManager().unregisterAll(this);
        if (store == null) return;

        if (Bukkit.isPrimaryThread()) {
            if (onlineRewardsEnabled) processOnlineRewards();
            syncOnlinePlayers();
        }
        long finalRevision = revision.get();
        Collection<StoredAccount> finalAccounts = snapshot();
        Collection<OnlineRewardState> finalRewards = onlineRewardSnapshot();
        Runnable finalSave = () -> {
            try {
                try {
                    store.save(finalAccounts);
                    acknowledgeBookings(finalRevision);
                } catch (Exception exception) {
                    DebugMessages.high("[XPVaultEconomy/Storage] Final account save failed: " + storageFailure(exception));
                }
                try {
                    if (onlineRewardStore != null) onlineRewardStore.save(finalRewards);
                } catch (Exception exception) {
                    DebugMessages.high("[XPVaultEconomy/Storage] Final online-reward save failed: " + storageFailure(exception));
                }
                try {
                    if (transactionHistory != null) transactionHistory.flush();
                } catch (Exception exception) {
                    // Informational journal failure must not prevent wallet cleanup.
                    DebugMessages.high("[XPVaultEconomy/Storage] Final transaction-history save failed: " + storageFailure(exception));
                }
            } finally {
                try {
                    store.close();
                } catch (Exception exception) {
                    DebugMessages.high("[XPVaultEconomy/Storage] Store close failed: " + storageFailure(exception));
                }
            }
        };
        if (storageExecutor == null) {
            finalSave.run();
            return;
        }
        // Behind any queued flush and off the server thread (JDBC in mysql mode).
        storageExecutor.execute(finalSave);
        storageExecutor.shutdown();
        try {
            if (!storageExecutor.awaitTermination(15, TimeUnit.SECONDS))
                DebugMessages.high("[XPVaultEconomy/Storage] Final save took longer than 15 seconds.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void applySettings(EconomySettings next) {
        settings = next;
        afkTimeoutMillis = Math.max(0L, Math.min(86_400L, getConfig().getLong("online-rewards.afk.pause-after-seconds", 0L))) * 1000L;
        startingBalance = clamp(next.startingBalance());
        currencySingular = next.currencySingular();
        currencyPlural = next.currencyPlural();
        onlineRewardsEnabled = next.onlineRewardsEnabled();
        onlineRewardBaseXp = next.onlineRewardBaseXp();
        onlineRewardGrowthMultiplier = next.onlineRewardGrowthMultiplier();
        onlineRewardIntervalMillis = next.onlineRewardIntervalMillis();
        onlineRewardGraceMillis = next.onlineRewardGraceMillis();
        onlineRewardCheckIntervalTicks = next.onlineRewardCheckIntervalTicks();
        onlineRewardNotificationsEnabled = next.notificationsEnabled();
        onlineRewardNotificationsActionBar = next.notificationsActionBar();
        onlineRewardMessage = next.notificationMessage();
        showOnlineRewardStatusInBalance = next.showStatusInBalance();
        topPageSize = next.topPageSize();
        topCacheMillis = next.topCacheMillis();
        if (leaderboard != null) leaderboard.invalidate();
    }

    private void scheduleOnlineRewards() {
        rewardTimer.cancel();
        rewardTimer.timer(onlineRewardCheckIntervalTicks, onlineRewardCheckIntervalTicks, this::processOnlineRewards);
    }

    /**
     * Re-reads the runtime-changeable values of config.yml. Accrued online time is settled with the old
     * rules before new reward rules apply; enabling the bonus starts series for online players.
     * Storage, sync intervals and economy.maximum-balance still require a restart.
     */
    void reloadSettings() {
        reloadConfig();
        if (lang != null) {
            lang.reload();
            applyLanguageSetting();
        }
        EconomySettings next = EconomySettings.read(getConfig(), maximumBalance);
        EconomySettings previous = settings;
        boolean wasEnabled = onlineRewardsEnabled;
        if (wasEnabled && next.rewardRulesDiffer(previous)) processOnlineRewards();
        applySettings(next);
        if (onlineRewardsEnabled) {
            if (!wasEnabled) {
                long now = System.currentTimeMillis();
                for (Player player : Bukkit.getOnlinePlayers()) handleOnlineRewardJoin(player, now);
            }
            if (!wasEnabled || previous == null
                    || previous.onlineRewardCheckIntervalTicks() != next.onlineRewardCheckIntervalTicks())
                scheduleOnlineRewards();
        } else if (rewardTimer != null) {
            rewardTimer.cancel();
        }
        if (web != null) {
            web.configChanged();
            for (Player player : Bukkit.getOnlinePlayers()) markWeb(player.getUniqueId());
        }
        DebugMessages.mid("[XPVaultEconomy] Settings reloaded; online bonus "
                + (onlineRewardsEnabled ? "on" : "off"));
    }

    /**
     * Admin dashboard ({@code /vadmin}): replaces Core's built-in catalog entry {@code economy} (info and set only) with
     * every {@code /economy admin} action plus balance lookup and leaderboard. Core runs them as the clicking admin
     * (normal permission checks) and asks for confirmation before balance changes and reloads.
     */
    static Vystorm.vystorm_core._uiMethods.admin.AdminModule adminModule(String pluginName, Lang lang) {
        String admin = "xpeconomy.admin";
        return new Vystorm.vystorm_core._uiMethods.admin.AdminModule("economy", lang.ref("admin.dashboard.title"),
                pluginName, "economy", admin, "economy account", List.of(
                adminAction(lang, "info", "admin.dashboard.info", admin, "admin info", "", "", false),
                adminAction(lang, "balance", "admin.dashboard.balance", "xpeconomy.balance.others", "balance", "<player>", "Steve", false),
                adminAction(lang, "top", "admin.dashboard.top", "xpeconomy.top", "top", "[page]", "1", false),
                adminAction(lang, "give", "admin.dashboard.give", admin, "admin give", "<player> <XP>", "Steve 100", true),
                adminAction(lang, "take", "admin.dashboard.take", admin, "admin take", "<player> <XP>", "Steve 100", true),
                adminAction(lang, "set", "admin.dashboard.set", admin, "admin set", "<player> <XP>", "Steve 500", true),
                adminAction(lang, "reload", "admin.dashboard.reload", admin, "admin reload", "", "", true)));
    }

    private static Vystorm.vystorm_core._uiMethods.admin.AdminAction adminAction(Lang lang, String id, String labelKey,
            String permission, String fixed, String hint, String example, boolean mutating) {
        return new Vystorm.vystorm_core._uiMethods.admin.AdminAction(id, lang.ref(labelKey), permission,
                fixed, hint, example, mutating);
    }

    private void registerServerSettings() {
        core.settings().register(this, Vystorm.vystorm_core._uiMethods.settings.ConfigSettings.of(this)
                .integer("economy.starting-balance", lang.ref("settings.starting-balance"),
                        lang.ref("settings.starting-balance-description"), 0, 10_000, 10)
                .add(currencyName("economy.currency-singular", "settings.currency-singular"))
                .add(currencyName("economy.currency-plural", "settings.currency-plural"))
                .toggle("online-rewards.enabled", lang.ref("settings.rewards-enabled"), lang.ref("settings.rewards-enabled-description"))
                .decimal("online-rewards.base-xp", lang.ref("settings.base-xp"), lang.ref("settings.base-xp-description"), 0, 100, 0.5)
                .decimal("online-rewards.growth-multiplier", lang.ref("settings.growth"),
                        lang.ref("settings.growth-description"), 1.0, 1.2, 0.005)
                .integer("online-rewards.interval-seconds", lang.ref("settings.interval"),
                        lang.ref("settings.interval-description"), 10, 3_600, 10)
                .integer("online-rewards.afk.pause-after-seconds", lang.ref("settings.afk"), lang.ref("settings.afk-description"), 0, 86_400, 1)
                .integer("online-rewards.reconnect-grace-minutes", lang.ref("settings.grace"),
                        lang.ref("settings.grace-description"), 0, 1_440, 5)
                .integer("online-rewards.check-interval-ticks", lang.ref("settings.check-interval"),
                        lang.ref("settings.check-interval-description"), 1, 1_200, 1)
                .toggle("online-rewards.show-status-in-expbal", lang.ref("settings.show-status"), lang.ref("settings.show-status-description"))
                .toggle("online-rewards.notifications.enabled", lang.ref("settings.notifications"), lang.ref("settings.notifications-description"))
                .toggle("online-rewards.notifications.action-bar", lang.ref("settings.action-bar"), lang.ref("settings.action-bar-description"))
                .text("online-rewards.notifications.message", lang.ref("settings.message"),
                        lang.ref("settings.message-description"), 200)
                .integer("top.page-size", lang.ref("settings.top-page-size"), lang.ref("settings.top-page-size-description"), 1, 50, 1)
                .integer("top.cache-seconds", lang.ref("settings.top-cache"),
                        lang.ref("settings.top-cache-description"), 0, 3_600, 5)
                .section("server", lang.ref("settings.section"), lang.ref("settings.section-description"),
                        "xpeconomy.admin", player -> reloadSettings()));
    }

    /** Registers the optional web page when Vystorm Core offers web pages (0.24.0+); everything works without it. */
    private void startWeb() {
        try {
            EconomyWeb page = new EconomyWeb(this, core);
            page.register();
            web = page;
        } catch (RuntimeException failure) {
            web = null;
            DebugMessages.high("[XPVaultEconomy web] Web page could not be registered; the plugin works without it", failure);
        }
    }

    /** Whether the web page can be opened right now (platform running, module registered). */
    boolean webAvailable() {
        return web != null && web.available();
    }

    /** Opens the web page (overlay with the Vystorm client mod, else a chat link). {@code subPath}: "", "pay", "top". */
    void openWeb(Player player, String subPath) {
        if (web == null) {
            lang.send(player, "web.unavailable");
            return;
        }
        web.open(player, subPath);
    }

    /** "[Web]" chat button, or an empty component while the web page is not available. */
    Component webButton(Player viewer, String subPath) {
        if (!webAvailable()) return Component.empty();
        return Component.space().append(core.message().chat().button(lang.plain(viewer, "button.web"),
                lang.plain(viewer, "button.web-hover"), player -> openWeb(player, subPath)));
    }

    private void markWeb(UUID uuid) {
        if (web != null && uuid != null) webDirty.add(uuid);
    }

    private void pushWebChanges() {
        if (web == null || webDirty.isEmpty()) return;
        for (UUID uuid : List.copyOf(webDirty)) {
            webDirty.remove(uuid);
            web.push(uuid);
        }
    }

    /** Currency name setting; empty means "use the language file". */
    private Vystorm.vystorm_core._uiMethods.settings.Setting currencyName(String path, String key) {
        return Vystorm.vystorm_core._uiMethods.settings.Setting.text(
                path.replaceAll("[^a-z0-9_.-]", "_"),
                lang.ref(key), lang.ref(key + "-description"), 32,
                player -> getConfig().getString(path, ""),
                (player, value) -> {
                    getConfig().set(path, value == null ? "" : value.trim());
                    return null;
                });
    }

    /** Applies the plugin's {@code language} config key (auto = follow Vystorm Core / the player's client). */
    private void applyLanguageSetting() {
        languageSetting = getConfig().getString("language", "auto");
        lang.setLanguage(languageSetting);
    }

    Lang lang() {
        return lang;
    }

    /**
     * /money, /pay, /baltop and /economy as Vystorm Core command trees ({@link EconomyCommands}). Registered in
     * onEnable so the commands answer "storage loading" until the accounts are loaded, as before.
     */
    private void registerCommands() {
        for (var spec : EconomyCommands.specs(EconomyCommands.target(this)))
            core.command().register(this, lang, spec);
    }

    private void putLoadedAccount(StoredAccount loaded) {
        StoredAccount account = new StoredAccount(
                loaded.uuid(), safeName(loaded.name()), clamp(loaded.balance()), loaded.initialized());
        accounts.put(account.uuid(), account);
        indexName(account.name(), account.uuid());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        handleJoin(event.getPlayer());
    }

    private void handleJoin(Player player) {
        StoredAccount stored = accounts.get(player.getUniqueId());
        if (stored == null) {
            updateAccount(player.getUniqueId(), player.getName(),
                    core.player().experience(player), true, false);
        } else if (!stored.initialized()) {
            // Vault may create a zero account before the first login. It remains
            // pending until native level plus XP progress can be imported here.
            long imported = clamp((long) core.player().experience(player) + stored.balance());
            updateAccount(player.getUniqueId(), player.getName(), imported, true, false);
            applyExperience(player, imported);
        } else {
            updateAccount(player.getUniqueId(), player.getName(), stored.balance(), true, false);
            applyExperience(player, stored.balance());
        }

        if (onlineRewardsEnabled) handleOnlineRewardJoin(player, System.currentTimeMillis());
        for (Player viewer : Bukkit.getOnlinePlayers())
            PersonalEconomyPrefs.refreshFavoriteName(core.settings().prefs(), this, viewer, player.getUniqueId(), player.getName());
        checkSavingGoal(player);
        markWeb(player.getUniqueId());
        if (standingOrders != null) standingOrders.join(player);
        showAwayIncome(player);
    }

    /** Notes a payment for a recipient who is offline, for the summary on their next join (economy.away-summary). */
    private void noteAwayIncome(OfflinePlayer recipient, String payer, long amount) {
        if (awayIncome == null || recipient.getPlayer() != null || !getConfig().getBoolean("economy.away-summary", true)) return;
        awayIncome.record(recipient.getUniqueId(), payer, amount);
    }

    /** "While you were away ..." a moment after joining, once; nothing when no payment came in. */
    private void showAwayIncome(Player player) {
        if (awayIncome == null) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            AwayIncome.Summary summary = awayIncome.take(player.getUniqueId());
            if (summary == null || !getConfig().getBoolean("economy.away-summary", true)) return;
            core.compat().platform().sendMessage(player, lang.component(player, summary.count() == 1 ? "away.summary-one" : "away.summary",
                    "amount", formatPoints(player, summary.total()), "count", summary.count(), "players", summary.names())
                    .append(Component.space()).append(accountButton(player)));
        }, 60L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (onlineRewardsEnabled) processOnlineReward(event.getPlayer(), System.currentTimeMillis());
        syncFromPlayer(event.getPlayer());
        queueFlush();
        markWeb(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExperienceChange(PlayerExpChangeEvent event) {
        scheduleSync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevelChange(PlayerLevelChangeEvent event) {
        scheduleSync(event.getPlayer());
    }

    /** Stage OBSERVE, registered in onEnable. */
    public void onDeath(PlayerDeathEvent event) {
        scheduleSync(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        scheduleSync(event.getPlayer());
    }

    private void scheduleSync(Player player) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                syncFromPlayer(player);
                checkSavingGoal(player);
            }
        });
    }

    private void syncOnlinePlayers() {
        if (!isEnabled()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            syncFromPlayer(player);
            checkSavingGoal(player);
        }
    }

    private void handleOnlineRewardJoin(Player player, long now) {
        if (rewardActivity != null) rewardActivity.action(player.getUniqueId());
        OnlineRewardState previous = onlineRewards.get(player.getUniqueId());
        boolean keepStreak = previous != null && withinGrace(previous, now, onlineRewardGraceMillis);
        OnlineRewardState next = keepStreak
                ? new OnlineRewardState(player.getUniqueId(), player.getName(),
                        previous.completedIntervals(), previous.fractionalXp(),
                        previous.partialIntervalMillis(), now)
                : new OnlineRewardState(player.getUniqueId(), player.getName(), 0L, 0.0, 0L, now);
        putOnlineReward(next);
    }

    private long afkGraceMillis() {
        return afkTimeoutMillis;
    }
    boolean rewardAfk(UUID id) { return rewardActivity != null && rewardActivity.afk(id); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRewardChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(this, () -> { if (rewardActivity != null && Bukkit.getPlayer(id) != null) rewardActivity.action(id); });
    }

    private void processOnlineRewards() {
        if (!isEnabled() || !onlineRewardsEnabled) return;
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) processOnlineReward(player, now);

        boolean removed = onlineRewards.entrySet().removeIf(entry ->
                Bukkit.getPlayer(entry.getKey()) == null
                        && !withinGrace(entry.getValue(), now, onlineRewardGraceMillis));
        if (removed) revision.incrementAndGet();
    }

    private void processOnlineReward(Player player, long now) {
        OnlineRewardState previous = onlineRewards.get(player.getUniqueId());
        if (previous == null) {
            handleOnlineRewardJoin(player, now);
            return;
        }

        long elapsed = rewardActivity == null ? Math.max(0L, now - previous.lastActiveEpochMillis())
                : rewardActivity.eligible(player.getUniqueId(), previous.lastActiveEpochMillis(), now);
        long accumulated = saturatingAdd(previous.partialIntervalMillis(), elapsed);
        long dueIntervals = accumulated / onlineRewardIntervalMillis;
        long remainder = accumulated % onlineRewardIntervalMillis;

        RewardGrant grant = calculateReward(onlineRewardBaseXp, onlineRewardGrowthMultiplier,
                previous.completedIntervals(), dueIntervals, previous.fractionalXp());
        OnlineRewardState next = new OnlineRewardState(player.getUniqueId(), player.getName(),
                grant.completedIntervals(), grant.fractionalXp(), remainder, now);
        putOnlineReward(next);

        if (grant.payout() <= 0L) return;
        long current = balance(player);
        long paid = Math.min(grant.payout(), Math.max(0L, maximumBalance - current));
        if (paid <= 0L) return;
        TransactionResult result = deposit(player, paid);
        if (result.success()) notifyOnlineReward(player, paid, next, result.balance());
        else DebugMessages.high("[XPVaultEconomy 337] Online bonus for " + player.getName() + " failed: "
                + errorText("en", result.error()));
    }

    private void putOnlineReward(OnlineRewardState state) {
        OnlineRewardState previous = onlineRewards.put(state.uuid(), state);
        if (!state.equals(previous)) revision.incrementAndGet();
        if (previous == null || previous.completedIntervals() != state.completedIntervals()) markWeb(state.uuid());
    }

    /** Online-bonus view for the web page; nothing is settled or paid. */
    RewardView rewardView(UUID uuid, boolean online) {
        OnlineRewardState state = onlineRewards.get(uuid);
        long now = System.currentTimeMillis();
        if (online && state != null && rewardActivity != null)
            now = state.lastActiveEpochMillis() + rewardActivity.eligible(uuid, state.lastActiveEpochMillis(), now);
        RewardView view = RewardView.of(state, online, onlineRewardsEnabled, now,
                onlineRewardIntervalMillis, onlineRewardGraceMillis, onlineRewardBaseXp, onlineRewardGrowthMultiplier);
        return online && onlineRewardsEnabled && rewardAfk(uuid) ? new RewardView("afk", view.streak(), view.nextXp(), view.intervalSeconds(), view.remainingSeconds(), 0) : view;
    }

    double onlineRewardBaseXp() {
        return onlineRewardBaseXp;
    }

    double onlineRewardGrowth() {
        return onlineRewardGrowthMultiplier;
    }

    long onlineRewardIntervalMillis() {
        return onlineRewardIntervalMillis;
    }

    long onlineRewardGraceMillis() {
        return onlineRewardGraceMillis;
    }

    static boolean withinGrace(OnlineRewardState state, long now, long graceMillis) {
        if (state == null || state.lastActiveEpochMillis() <= 0L) return false;
        long elapsed = Math.max(0L, now - state.lastActiveEpochMillis());
        return elapsed <= Math.max(0L, graceMillis);
    }

    static RewardGrant calculateReward(double baseXp, double multiplier, long completedIntervals,
                                       long dueIntervals, double fractionalXp) {
        long completed = Math.max(0L, completedIntervals);
        long due = Math.max(0L, dueIntervals);
        double carry = Double.isFinite(fractionalXp) && fractionalXp >= 0.0 && fractionalXp < 1.0
                ? fractionalXp : 0.0;
        if (due == 0L || baseXp <= 0.0)
            return new RewardGrant(0L, carry, completed);

        double growth = Math.max(1.0, multiplier);
        double first = baseXp * StrictMath.pow(growth, completed);
        double series = growth == 1.0
                ? first * due
                : first * (StrictMath.pow(growth, due) - 1.0) / (growth - 1.0);
        double total = series + carry;
        long payout;
        double remaining;
        if (!Double.isFinite(total) || total >= Long.MAX_VALUE) {
            payout = Long.MAX_VALUE;
            remaining = 0.0;
        } else {
            payout = Math.max(0L, (long) StrictMath.floor(total + 1.0E-9));
            remaining = Math.max(0.0, Math.min(0.999999999, total - payout));
        }
        return new RewardGrant(payout, remaining, saturatingAdd(completed, due));
    }

    private static long saturatingAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }

    private double nextOnlineReward(OnlineRewardState state) {
        double reward = onlineRewardBaseXp
                * StrictMath.pow(onlineRewardGrowthMultiplier, state.completedIntervals());
        return Double.isFinite(reward) ? reward : maximumBalance;
    }

    private void notifyOnlineReward(Player player, long paid, OnlineRewardState state, long newBalance) {
        if (!onlineRewardNotificationsEnabled) return;
        String language = lang.language(player);
        Object[] values = {"xp", Long.toString(paid), "streak", Long.toString(state.completedIntervals()),
                "next_xp", formatReward(language, nextOnlineReward(state)),
                "multiplier", formatReward(language, onlineRewardGrowthMultiplier),
                "balance", Long.toString(newBalance)};
        Component component;
        if (onlineRewardMessage == null || onlineRewardMessage.isBlank()) {
            component = lang.component(player, "reward.notification", values);
        } else {
            // Custom text from config.yml: plain text, {placeholders} replaced, shown in green as before.
            String message = onlineRewardMessage;
            for (int i = 0; i + 1 < values.length; i += 2)
                message = message.replace("{" + values[i] + "}", String.valueOf(values[i + 1]));
            component = Component.text(message, NamedTextColor.GREEN);
        }
        if (client != null && client.rewardToast(player, paid, state.completedIntervals(),
                formatReward(language, nextOnlineReward(state)))) return;
        if (onlineRewardNotificationsActionBar) core.compat().platform().sendActionBar(player, component);
        else core.compat().platform().sendMessage(player, component.append(Component.space()).append(accountButton(player)));
    }

    private void syncFromPlayer(Player player) {
        updateAccount(player.getUniqueId(), player.getName(),
                core.player().experience(player), true, false);
    }

    private void applyExperience(Player player, long balance) {
        Runnable apply = () -> {
            if (player.isOnline()) core.player().setExperience(player, (int) clamp(balance));
        };
        if (Bukkit.isPrimaryThread()) apply.run();
        else Bukkit.getScheduler().runTask(this, apply);
    }

    private StoredAccount updateAccount(UUID uuid, String name, long balance,
                                        boolean initialized, boolean applyToPlayer) {
        long clamped = clamp(balance);
        String safeName = safeName(name);
        StoredAccount previous = accounts.get(uuid);
        // Accounts without a player (e.g. NPCs) have no OfflinePlayer name: do not overwrite a stored name with ""
        if (safeName.isBlank() && previous != null && !previous.name().isBlank()) safeName = previous.name();
        if (previous != null && previous.balance() == clamped && previous.name().equals(safeName)
                && previous.initialized() == initialized) {
            if (applyToPlayer) {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) applyExperience(online, clamped);
            }
            return previous;
        }

        StoredAccount next = new StoredAccount(uuid, safeName, clamped, initialized);
        accounts.put(uuid, next);
        if (previous == null || previous.balance() != clamped) markWeb(uuid);
        if (previous != null && !previous.name().equalsIgnoreCase(safeName))
            names.remove(previous.name().toLowerCase(Locale.ROOT), uuid);
        indexName(safeName, uuid);
        revision.incrementAndGet();

        if (applyToPlayer) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) applyExperience(online, clamped);
        }
        return next;
    }

    private void indexName(String name, UUID uuid) {
        if (name != null && !name.isBlank()) names.put(name.toLowerCase(Locale.ROOT), uuid);
    }

    private String safeName(String name) {
        return name == null ? "" : name.trim();
    }

    boolean hasAccount(UUID uuid) {
        return accounts.containsKey(uuid);
    }

    boolean createAccount(OfflinePlayer player) {
        synchronized (transactionLock) {
            if (accounts.containsKey(player.getUniqueId())) return false;
            Player online = Bukkit.getPlayer(player.getUniqueId());
            long initial = online == null ? startingBalance : core.player().experience(online);
            updateAccount(player.getUniqueId(), player.getName(), initial, online != null, false);
            return true;
        }
    }

    boolean createAccount(UUID uuid, String name) {
        synchronized (transactionLock) {
            if (accounts.containsKey(uuid)) return false;
            Player online = Bukkit.getPlayer(uuid);
            long initial = online == null ? startingBalance : core.player().experience(online);
            updateAccount(uuid, name == null ? (online == null ? "" : online.getName()) : name,
                    initial, online != null, false);
            return true;
        }
    }

    boolean renameAccount(UUID uuid, String newName) {
        synchronized (transactionLock) {
            StoredAccount current = accounts.get(uuid);
            if (current == null || newName == null || newName.isBlank()) return false;
            updateAccount(uuid, newName, current.balance(), current.initialized(), false);
            return true;
        }
    }

    Map<UUID, String> accountNames() {
        Map<UUID, String> result = new ConcurrentHashMap<>();
        accounts.forEach((uuid, account) -> result.put(uuid, account.name()));
        return Map.copyOf(result);
    }

    /** Player name, else the stored account name (accounts created through Vault), else the UUID. */
    String displayName(OfflinePlayer player) {
        if (player.getName() != null) return player.getName();
        return accountName(player.getUniqueId()).orElse(player.getUniqueId().toString());
    }

    Optional<String> accountName(UUID uuid) {
        StoredAccount account = accounts.get(uuid);
        return account == null || account.name().isBlank() ? Optional.empty() : Optional.of(account.name());
    }

    long balance(OfflinePlayer player) {
        Player online = Bukkit.getPlayer(player.getUniqueId());
        if (online != null && Bukkit.isPrimaryThread()) syncFromPlayer(online);
        StoredAccount account = accounts.get(player.getUniqueId());
        return account == null ? 0L : account.balance();
    }

    TransactionResult withdraw(OfflinePlayer player, long amount) {
        synchronized (transactionLock) {
            if (amount < 0L) return TransactionResult.failure(balance(player), "error.negative-amount");
            createAccountIfMissing(player);
            long current = balance(player);
            if (current < amount) return TransactionResult.failure(current, "error.insufficient");
            long updated = current - amount;
            StoredAccount state = accounts.get(player.getUniqueId());
            updateAccount(player.getUniqueId(), player.getName(), updated,
                    state != null && state.initialized(), true);
            if (!paymentJournal.get() && transactionHistory != null) transactionHistory.record(UUID.randomUUID(), player.getUniqueId(), null, "debit", current, updated);
            return TransactionResult.success(amount, updated);
        }
    }

    TransactionResult deposit(OfflinePlayer player, long amount) {
        synchronized (transactionLock) {
            if (amount < 0L) return TransactionResult.failure(balance(player), "error.negative-amount");
            createAccountIfMissing(player);
            long current = balance(player);
            if (amount > maximumBalance - current)
                return TransactionResult.failure(current, "error.maximum-exceeded");
            long updated = current + amount;
            StoredAccount state = accounts.get(player.getUniqueId());
            updateAccount(player.getUniqueId(), player.getName(), updated,
                    state != null && state.initialized(), true);
            if (!paymentJournal.get() && transactionHistory != null) transactionHistory.record(UUID.randomUUID(), player.getUniqueId(), null, "credit", current, updated);
            return TransactionResult.success(amount, updated);
        }
    }

    TransactionResult setBalance(OfflinePlayer player, long amount) {
        synchronized (transactionLock) {
            if (amount < 0L || amount > maximumBalance)
                return TransactionResult.failure(balance(player), "error.out-of-range");
            createAccountIfMissing(player);
            long before = balance(player);
            updateAccount(player.getUniqueId(), player.getName(), amount, true, true);
            if (transactionHistory != null) transactionHistory.record(UUID.randomUUID(), player.getUniqueId(), null, "set", before, amount);
            return TransactionResult.success(amount, amount);
        }
    }

    private void createAccountIfMissing(OfflinePlayer player) {
        if (!accounts.containsKey(player.getUniqueId())) {
            Player online = Bukkit.getPlayer(player.getUniqueId());
            long initial = online == null ? startingBalance : core.player().experience(online);
            updateAccount(player.getUniqueId(), player.getName(), initial, online != null, false);
        }
    }

    OfflinePlayer resolveKnownPlayer(String name) {
        if (name == null || name.isBlank()) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;

        UUID uuid = names.get(name.toLowerCase(Locale.ROOT));
        if (uuid != null) return Bukkit.getOfflinePlayer(uuid);

        return core.compat().platform().getOfflinePlayerIfCached(name);
    }

    private long clamp(long value) {
        return Math.max(0L, Math.min(maximumBalance, value));
    }

    private Collection<StoredAccount> snapshot() {
        return new ArrayList<>(accounts.values());
    }

    private Collection<OnlineRewardState> onlineRewardSnapshot() {
        return new ArrayList<>(onlineRewards.values());
    }

    /** All accounts for the leaderboard; online players count with their current live XP. Main thread only. */
    private Collection<StoredAccount> leaderboardSnapshot() {
        Map<UUID, StoredAccount> copy = new HashMap<>(accounts);
        // top.hide-name-prefixes: e.g. "npc_" hides NPC accounts from /baltop
        List<String> hidden = getConfig().getStringList("top.hide-name-prefixes");
        if (!hidden.isEmpty())
            copy.values().removeIf(account -> account.name() != null && hidden.stream().anyMatch(prefix -> !prefix.isEmpty()
                    && account.name().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))));
        for (Player player : Bukkit.getOnlinePlayers())
            copy.put(player.getUniqueId(), new StoredAccount(player.getUniqueId(), safeName(player.getName()),
                    clamp(core.player().experience(player)), true));
        copy.keySet().removeIf(id -> privacy == null || privacy.hidden(id));
        return copy.values();
    }

    /**
     * Hands the cached or freshly sorted leaderboard to {@code callback} on the main thread; sorting
     * runs on the storage executor. The callback receives null if no ranking is available.
     */
    void requestLeaderboard(Consumer<BalanceRanking> callback) {
        if (leaderboard == null || !isEnabled()) {
            callback.accept(null);
            return;
        }
        leaderboard.request(topCacheMillis, this::leaderboardSnapshot, callback);
    }

    int topPageSize() {
        return topPageSize;
    }

    /** "#3 Name: 1,234 XP points (level 30)"; the viewer's own row is highlighted. */
    Component leaderboardLine(CommandSender viewer, BalanceRanking.Entry entry, boolean own) {
        return lang.component(viewer, "top.line", "rank", entry.rank(),
                "name", Component.text(entry.name(), own ? NamedTextColor.GREEN : NamedTextColor.WHITE),
                "amount", formatPoints(viewer, entry.balance()),
                "level", BalanceRanking.levelForPoints(entry.balance()));
    }

    /** Saves account changes made outside the Vault provider soon. */
    void changed() {
        if (Bukkit.isPrimaryThread()) queueFlush();
    }

    private void queueFlush() {
        if (storageExecutor == null || storageExecutor.isShutdown()) return;
        long targetRevision = revision.get();
        if (targetRevision <= savedRevision || !flushQueued.compareAndSet(false, true)) return;
        Collection<StoredAccount> snapshot = snapshot();
        Collection<OnlineRewardState> rewardSnapshot = onlineRewardSnapshot();

        storageExecutor.execute(() -> {
            try {
                store.save(snapshot);
                if (onlineRewardStore != null) onlineRewardStore.save(rewardSnapshot);
                if (transactionHistory != null) transactionHistory.flush();
                savedRevision = Math.max(savedRevision, targetRevision);
                acknowledgeBookings(targetRevision);
                if (failedFlushes > 0) {
                    DebugMessages.mid("[XPVaultEconomy 587] Saving works again; current state saved after "
                            + failedFlushes + " failed attempts.");
                    failedFlushes = 0;
                }
            } catch (Exception exception) {
                failedFlushes++;
                if (failedFlushes == 1 || failedFlushes % 12 == 0)
                    DebugMessages.high("[XPVaultEconomy 594] Saving failed (attempt " + failedFlushes
                            + "); balances stay in memory, retrying at the next save interval: "
                            + storageFailure(exception));
            } finally {
                flushQueued.set(false);
            }
        });
    }

    private static String storageFailure(Exception exception) {
        var details = new java.io.StringWriter();
        if (exception instanceof java.sql.SQLException sql)
            details.append("SQLState=").append(String.valueOf(sql.getSQLState()))
                    .append(", error code=").append(String.valueOf(sql.getErrorCode())).append('\n');
        exception.printStackTrace(new java.io.PrintWriter(details));
        return details.toString();
    }

    /** Configured singular currency name; blank = the language file decides (per viewer). */
    String currencySingular() {
        return currencyName(apiLanguage(), true);
    }

    /** Configured plural currency name; blank = the language file decides (per viewer). */
    String currencyPlural() {
        return currencyName(apiLanguage(), false);
    }

    String currencyName(String language, boolean singular) {
        String configured = singular ? currencySingular : currencyPlural;
        if (configured != null && !configured.isBlank()) return configured;
        return lang.plain(language, singular ? "currency.singular" : "currency.plural");
    }

    long maxBalance() {
        return maximumBalance;
    }

    /**
     * Language for texts handed to other plugins through Vault (format, currency names, error messages), where no
     * viewer is known: the plugin's fixed {@code language}, else Core's default (console). A config from before 1.6.0
     * that still carries the former German currency defaults keeps German output here, as before.
     */
    String apiLanguage() {
        if (lang == null) return "en";
        boolean auto = languageSetting == null || languageSetting.isBlank() || languageSetting.equalsIgnoreCase("auto");
        if (auto && settings != null && settings.legacyGermanDefaults()) return lang.resolveLanguage("de");
        return lang.language(Bukkit.getConsoleSender());
    }

    /** Text for other plugins through Vault, in {@link #apiLanguage()}. */
    String apiText(String key, Object... placeholders) {
        return lang == null ? key : lang.plain(apiLanguage(), key, placeholders);
    }

    /** Error text of a {@link TransactionResult} key in a language. */
    String errorText(String language, String key) {
        return lang.plain(language, key, "currency", currencyName(language, false));
    }

    static Locale numberLocale(String language) {
        Locale locale = Locale.forLanguageTag(language == null ? "en" : language.replace('_', '-'));
        return locale.getLanguage().isEmpty() ? Locale.ENGLISH : locale;
    }

    /** "1,234 XP points" (English) / "1.234 XP-Punkte" (German). */
    String formatPoints(String language, long points) {
        return NumberFormat.getIntegerInstance(numberLocale(language)).format(points) + " "
                + currencyName(language, points == 1L);
    }

    String formatPoints(CommandSender viewer, long points) {
        return formatPoints(lang.language(viewer), points);
    }

    /** Amount for Vault's {@code format()}. */
    String formatPointsForApi(long points) {
        return formatPoints(apiLanguage(), points);
    }

    static String formatReward(String language, double value) {
        DecimalFormat format = (DecimalFormat) NumberFormat.getNumberInstance(numberLocale(language));
        format.applyPattern("#,##0.##");
        return format.format(value);
    }

    /**
     * Runs {@code /<command> <args>} with English subcommand names and keywords (the Core command tree translates
     * localized input before it gets here). {@code false}: unknown form, the caller shows the usage line.
     */
    boolean dispatch(CommandSender sender, String command, String[] args) {
        if (!ready) {
            lang.send(sender, "storage.loading");
            return true;
        }
        EconomyCommandRoute route = EconomyCommandRoute.parse(command, args);
        if (route.permission().isEmpty()) return false;
        if (!sender.hasPermission(route.permission())) {
            lang.send(sender, "common.no-permission");
            return true;
        }
        if (sender instanceof Player player && (EconomyCommandRoute.isAccountAction(route.action())
                || (command.equalsIgnoreCase("economy") && args.length == 0))) {
            openAccount(player);
            return true;
        }
        if (route.action().equals("web")) {
            if (sender instanceof Player player) openWeb(player, "");
            else lang.send(sender, "common.players-only");
            return true;
        }
        return switch (route.action()) {
            case "balance", "guthaben", "konto", "account", "menu" -> balanceCommand(sender, route.arguments());
            case "pay", "zahlen" -> payCommand(sender, route.arguments());
            case "privacy" -> privacyCommand(sender, route.arguments());
            case "history" -> historyCommand(sender, route.arguments());
            case "statement" -> new StatementCommands(this, core, transactionHistory, core.data().files()).command(sender, route.arguments());
            case "request", "requests", "split", "requestblock", "limits" -> paymentCommands.command(sender, route.action(), route.arguments());
            case "goal", "ziel" -> goalCommand(sender, route.arguments());
            case "favorite", "favorit" -> favoriteCommand(sender, route.arguments());
            case "top", "rangliste" -> topCommand(sender, route.arguments());
            case "cheque", "scheck", "withdraw" -> cheques == null ? false : cheques.command(sender, route.arguments());
            case "bank", "kasse" -> banks == null ? false : banks.command(sender, route.arguments());
            case "standing", "dauerauftrag" -> standingOrders == null ? false : standingOrders.command(sender, route.arguments());
            case "term", "festgeld" -> termDeposits == null ? false : termDeposits.command(sender, route.arguments());
            case "fund", "spende" -> funds == null ? false : funds.command(sender, route.arguments());
            case "admin" -> adminCommand(sender, route.arguments());
            default -> false;
        };
    }

    private boolean balanceCommand(CommandSender sender, String[] args) {
        OfflinePlayer target;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                lang.send(sender, "balance.console-usage");
                return true;
            }
            target = player;
        } else {
            if (!sender.hasPermission("xpeconomy.balance.others")) {
                lang.send(sender, "common.no-permission");
                return true;
            }
            target = resolveKnownPlayer(args[0]);
            if (target == null || !hasAccount(target.getUniqueId())) {
                core.compat().platform().sendMessage(sender, lang.component(sender, "common.unknown-account").colorIfAbsent(NamedTextColor.RED));
                return true;
            }
        }

        if (!balanceVisible(sender instanceof Player p ? p.getUniqueId() : null, sender.hasPermission("xpeconomy.admin"), target.getUniqueId())) {
            lang.send(sender, "privacy.unavailable"); return true;
        }
        String targetName = displayName(target);
        Component line = lang.component(sender, "balance.line", "player", targetName,
                "amount", formatPoints(sender, balance(target)));
        if (sender instanceof Player viewer && viewer.getUniqueId().equals(target.getUniqueId())) {
            line = line.append(Component.space()).append(accountButton(viewer));
            if (viewer.hasPermission("xpeconomy.pay"))
                line = line.append(Component.space()).append(core.message().chat().suggest(lang.plain(viewer, "button.pay"),
                        lang.plain(viewer, "button.pay-hover"), "/pay "));
            line = line.append(webButton(viewer, ""));
        } else if (sender.hasPermission("xpeconomy.admin") && target.getName() != null) {
            line = line.append(Component.space()).append(core.message().chat().suggest(lang.plain(sender, "button.admin-edit"),
                    lang.plain(sender, "button.admin-edit-hover"), "/economy admin give " + target.getName() + " "));
        }
        core.compat().platform().sendMessage(sender, line);
        if (onlineRewardsEnabled && showOnlineRewardStatusInBalance)
            core.compat().platform().sendMessage(sender, onlineRewardStatus(sender, target));
        return true;
    }

    boolean balanceVisible(UUID viewer, boolean administrator, UUID target) {
        return administrator || target.equals(viewer) || (privacy != null && !privacy.hidden(target));
    }

    private String setPrivacy(Player player, boolean hidden) {
        try {
            privacy.set(player.getUniqueId(), hidden);
            if (leaderboard != null) leaderboard.invalidate();
            for (Player online : Bukkit.getOnlinePlayers()) markWeb(online.getUniqueId());
            return null;
        } catch (java.io.IOException ex) {
            getLogger().warning("Privacy preference could not be saved: " + ex.getMessage());
            return lang.plain(player, "privacy.save-failed");
        }
    }

    void openStatement(Player player) {
        new StatementCommands(this, core, transactionHistory, core.data().files()).command(player, new String[0]);
    }

    private boolean historyCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { lang.send(sender, "common.players-only"); return true; }
        if (transactionHistory == null || !transactionHistory.readable()) { lang.send(player, "history.unavailable"); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("export")) {
            try {
                var file = transactionHistory.export(getDataFolder().toPath().resolve("exports"), player.getUniqueId());
                lang.send(player, "history.exported", "file", file.getFileName().toString());
            } catch (java.io.IOException failure) { lang.send(player, "history.unavailable"); }
            return true;
        }
        if (args.length != 0) { lang.send(player, "history.usage"); return true; }
        lang.send(player, "history.scope");
        var rows = transactionHistory.recent(player.getUniqueId(), 10);
        if (rows.isEmpty()) lang.send(player, "history.empty");
        for (var b : rows) lang.send(player, "history.row", "id", b.paymentId(), "time", java.time.Instant.ofEpochMilli(b.time()),
                "cause", lang.component(player, historyCause(b.cause())), "delta", b.after() - b.before(), "balance", b.after());
        return true;
    }
    static String historyCause(String cause) {
        return switch (cause) {
            case "pay" -> "history.cause.pay";
            case "bank" -> "history.cause.bank";
            case "standing" -> "history.cause.standing";
            case "term" -> "history.cause.term";
            case "fund" -> "history.cause.fund";
            case "debit" -> "history.cause.debit";
            case "credit" -> "history.cause.credit";
            default -> "history.cause.set";
        };
    }

    private boolean privacyCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { lang.send(sender, "common.players-only"); return true; }
        if (args.length != 1 || !(args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            lang.send(player, "privacy.usage"); return true;
        }
        boolean hidden = args[0].equalsIgnoreCase("on");
        String error = setPrivacy(player, hidden);
        if (error != null) core.compat().platform().sendMessage(player, Component.text(error));
        else lang.send(player, hidden ? "privacy.enabled" : "privacy.disabled");
        return true;
    }

    private boolean goalCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, "common.players-only");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("show") || args[0].equalsIgnoreCase("anzeigen")) {
            core.compat().platform().sendMessage(player, savingGoalStatus(player));
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("clear") || action.equals("remove") || action.equals("löschen")) {
            PersonalEconomyPrefs.clearGoal(core.settings().prefs(), this, player);
            lang.send(player, "goal.cleared");
            markWeb(player.getUniqueId());
            return true;
        }
        if (action.equals("notify") || action.equals("benachrichtigung")) {
            if (args.length != 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off")
                    || args[1].equalsIgnoreCase("an") || args[1].equalsIgnoreCase("aus"))) {
                lang.send(player, "goal.notify-usage");
                return true;
            }
            boolean enabled = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("an");
            core.settings().prefs().setBool(this, player, PersonalEconomyPrefs.GOAL_NOTIFY, enabled);
            if (enabled) core.settings().prefs().setNumber(this, player, PersonalEconomyPrefs.GOAL_NOTIFIED_TARGET, 0.0D);
            lang.send(player, enabled ? "goal.notify-on" : "goal.notify-off");
            checkSavingGoal(player);
            markWeb(player.getUniqueId());
            return true;
        }
        if (!(action.equals("set") || action.equals("setzen")) || args.length < 2) {
            lang.send(player, "goal.usage");
            return true;
        }
        Long target = parsePoints(args[1]);
        if (target == null || target <= 0L) {
            lang.send(player, "goal.bad-target", "max", formatPoints(player, maxBalance()));
            return true;
        }
        String label = PersonalEconomyPrefs.cleanLabel(args.length > 2
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "");
        PersonalEconomyPrefs.setGoal(core.settings().prefs(), this, player, target, label);
        if (target > 0) {
            core.gui().onboarding().markFeature(this, player);
        }
        lang.send(player, "goal.set", "label", label.isBlank() ? lang.plain(player, "goal.default-label") : label,
                "target", formatPoints(player, target));
        checkSavingGoal(player);
        markWeb(player.getUniqueId());
        return true;
    }

    Component savingGoalStatus(Player player) {
        PersonalEconomyPrefs.Goal goal = PersonalEconomyPrefs.goal(core.settings().prefs(), this, player);
        if (goal == null) return lang.component(player, "goal.none");
        long current = balance(player);
        long progress = Math.min(current, goal.target());
        int percent = (int) Math.min(100L, progress * 100L / goal.target());
        return lang.component(player, "goal.progress",
                "label", goal.label().isBlank() ? lang.plain(player, "goal.default-label") : goal.label(),
                "current", formatPoints(player, current), "target", formatPoints(player, goal.target()),
                "percent", percent, "notify", lang.plain(player, goal.notifyOnReach() ? "goal.notify-on-short" : "goal.notify-off-short"));
    }

    private void checkSavingGoal(Player player) {
        PersonalEconomyPrefs.Goal goal = PersonalEconomyPrefs.goal(core.settings().prefs(), this, player);
        if (goal == null || !goal.notifyOnReach()) return;
        double notified = core.settings().prefs().number(this, player, PersonalEconomyPrefs.GOAL_NOTIFIED_TARGET, 0.0D);
        if (notified == goal.target() || balance(player) < goal.target()) return;
        core.settings().prefs().setNumber(this, player, PersonalEconomyPrefs.GOAL_NOTIFIED_TARGET, goal.target());
        Component reached = lang.component(player, "goal.reached", "label", goal.label().isBlank()
                ? lang.plain(player, "goal.default-label") : goal.label(),
                "target", formatPoints(player, goal.target()));
        core.message().routine().send(this, player, reached);
    }

    List<PersonalEconomyPrefs.Payee> favorites(Player player) {
        List<PersonalEconomyPrefs.Payee> result = new ArrayList<>();
        for (PersonalEconomyPrefs.Payee favorite : PersonalEconomyPrefs.favorites(core.settings().prefs(), this, player)) {
            OfflinePlayer current = Bukkit.getOfflinePlayer(favorite.uuid());
            String name = current.getName();
            if (name != null && !name.isBlank()) {
                PersonalEconomyPrefs.refreshFavoriteName(core.settings().prefs(), this, player, favorite.uuid(), name);
                result.add(new PersonalEconomyPrefs.Payee(favorite.uuid(), name));
            } else result.add(favorite);
        }
        return List.copyOf(result);
    }

    String favoriteName(Player player, UUID uuid) {
        return favorites(player).stream().filter(favorite -> favorite.uuid().equals(uuid))
                .map(PersonalEconomyPrefs.Payee::name).findFirst().orElse("");
    }

    OfflinePlayer player(UUID uuid) {
        return uuid == null ? null : Bukkit.getOfflinePlayer(uuid);
    }

    private boolean favoriteCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, "common.players-only");
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("pay")) {
            UUID id;
            try { id = UUID.fromString(args[1]); }
            catch (IllegalArgumentException ex) { lang.send(player, "common.unknown-account"); return true; }
            if (favorites(player).stream().noneMatch(f -> f.uuid().equals(id))) { lang.send(player, "favorite.not-saved", "name", args[1]); return true; }
            OfflinePlayer target = player(id); Long amount = parsePoints(args[2]);
            Component error = checkTransfer(player, target, amount);
            if (error == null && balance(player) < amount) error = Component.text(errorText(lang.language(player), "error.insufficient"));
            if (error != null) core.compat().platform().sendMessage(player, error);
            else accountMenu.confirmTransfer(player, target, amount);
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("liste")) {
            List<PersonalEconomyPrefs.Payee> payees = favorites(player);
            if (payees.isEmpty()) {
                lang.send(player, "favorite.empty");
                return true;
            }
            lang.send(player, "favorite.header");
            for (int i = 0; i < payees.size(); i++) {
                PersonalEconomyPrefs.Payee payee = payees.get(i);
                core.compat().platform().sendMessage(player, lang.component(player, "favorite.entry", "index", i + 1, "name", payee.name())
                        .append(Component.space()).append(core.message().chat().suggest(lang.plain(player, "button.pay"),
                                lang.plain(player, "button.pay-hover"), "/economy favorite pay " + payee.uuid() + " ")));
            }
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length != 2 || !(action.equals("add") || action.equals("hinzufügen")
                || action.equals("remove") || action.equals("entfernen"))) {
            lang.send(player, "favorite.usage");
            return true;
        }
        OfflinePlayer target = resolveKnownPlayer(args[1]);
        if (target == null || !hasAccount(target.getUniqueId())) {
            lang.send(player, "common.unknown-account");
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            lang.send(player, "pay.self");
            return true;
        }
        String targetName = displayName(target);
        if (action.equals("add") || action.equals("hinzufügen")) {
            boolean added = PersonalEconomyPrefs.addFavorite(core.settings().prefs(), this, player, target.getUniqueId(), targetName);
            lang.send(player, added ? "favorite.added" : "favorite.limit", "name", targetName,
                    "limit", PersonalEconomyPrefs.MAX_FAVORITES);
        } else {
            boolean removed = PersonalEconomyPrefs.removeFavorite(core.settings().prefs(), this, player, target.getUniqueId());
            lang.send(player, removed ? "favorite.removed" : "favorite.not-saved", "name", targetName);
        }
        return true;
    }

    boolean onlineRewardsEnabled() {
        return onlineRewardsEnabled;
    }

    /** Current online-reward status of an account for a viewer; settles accrued time for online players first. */
    Component onlineRewardStatus(CommandSender viewer, OfflinePlayer target) {
        if (!onlineRewardsEnabled) return lang.component(viewer, "reward.status-disabled");
        OnlineRewardState state = onlineRewards.get(target.getUniqueId());
        if (state == null) return lang.component(viewer, "reward.status-none");

        Player online = Bukkit.getPlayer(target.getUniqueId());
        long now = System.currentTimeMillis();
        if (online != null) {
            processOnlineReward(online, now);
            state = onlineRewards.get(target.getUniqueId());
            if (rewardAfk(target.getUniqueId())) return lang.component(viewer, "reward.status-afk", "streak", state.completedIntervals());
            long seconds = Math.max(1L,
                    (onlineRewardIntervalMillis - state.partialIntervalMillis() + 999L) / 1000L);
            return lang.component(viewer, "reward.status-active", "streak", state.completedIntervals(),
                    "next_xp", formatReward(lang.language(viewer), nextOnlineReward(state)), "seconds", seconds);
        }

        long remaining = onlineRewardGraceMillis - Math.max(0L, now - state.lastActiveEpochMillis());
        if (remaining < 0L) return lang.component(viewer, "reward.status-none");
        long minutes = (remaining + 59_999L) / 60_000L;
        return lang.component(viewer, "reward.status-paused", "streak", state.completedIntervals(), "minutes", minutes);
    }

    private boolean topCommand(CommandSender sender, String[] args) {
        int page = 1;
        if (args.length > 0) {
            Integer parsed = EconomyCommandRoute.parsePage(args[0]);
            if (parsed == null) {
                lang.send(sender, "top.bad-page");
                return true;
            }
            page = parsed;
        }
        int requested = page;
        requestLeaderboard(ranking -> {
            if (sender instanceof Player player && !player.isOnline()) return;
            if (ranking == null) lang.send(sender, "top.unavailable");
            else sendLeaderboard(sender, ranking, requested);
        });
        return true;
    }

    private void sendLeaderboard(CommandSender sender, BalanceRanking ranking, int requested) {
        if (ranking.size() == 0) {
            lang.send(sender, "top.empty");
            return;
        }
        BalanceRanking.Page page = ranking.page(requested, topPageSize);
        long age = Math.max(0L, (System.currentTimeMillis() - ranking.createdAtMillis()) / 1000L);
        lang.send(sender, "top.header", "page", page.page(), "pages", page.pages(), "total", page.total(),
                "age", age > 0L ? lang.plain(sender, "top.header-age", "seconds", age) : "");
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        boolean ownOnPage = false;
        for (BalanceRanking.Entry entry : page.entries()) {
            boolean own = entry.uuid().equals(viewer);
            ownOnPage |= own;
            core.compat().platform().sendMessage(sender, leaderboardLine(sender, entry, own));
        }
        if (viewer != null && !ownOnPage)
            ranking.find(viewer).ifPresent(own -> core.compat().platform().sendMessage(sender,
                    lang.component(sender, "top.own", "line", leaderboardLine(sender, own, true))));
        if (!(sender instanceof Player player)) return;
        Component navigation = Component.empty();
        if (page.page() > 1)
            navigation = navigation.append(core.message().chat().command(lang.plain(player, "top.previous"),
                    lang.plain(player, "top.page-hover", "page", page.page() - 1),
                    "/baltop " + (page.page() - 1))).append(Component.space());
        if (page.page() < page.pages())
            navigation = navigation.append(core.message().chat().command(lang.plain(player, "top.next"),
                    lang.plain(player, "top.page-hover", "page", page.page() + 1),
                    "/baltop " + (page.page() + 1))).append(Component.space());
        core.compat().platform().sendMessage(sender, navigation.append(accountButton(player)).append(webButton(player, "top")));
    }

    private boolean payCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, "common.players-only");
            return true;
        }
        if (args.length != 2) {
            core.compat().platform().sendMessage(sender, lang.component(player, "pay.usage")
                    .append(core.message().chat().suggest(lang.plain(player, "button.pay"), lang.plain(player, "button.pay-usage-hover"),
                            args.length == 1 ? "/pay " + args[0] + " " : "/pay "))
                    .append(Component.space()).append(accountButton(player)));
            return true;
        }

        OfflinePlayer target = resolveKnownPlayer(args[0]);
        Long amount = parsePoints(args[1]);
        Component error = checkTransfer(player, target, amount);
        if (error == null && balance(player) < amount) error = Component.text(errorText(lang.language(player), "error.insufficient"));
        if (error == null) { accountMenu.confirmTransfer(player, target, amount); return true; }
        if (error != null) {
            Component line = error.colorIfAbsent(NamedTextColor.RED).append(Component.space());
            boolean knownTarget = target != null && target.getName() != null && hasAccount(target.getUniqueId())
                    && !target.getUniqueId().equals(player.getUniqueId());
            line = line.append(core.message().chat().suggest(lang.plain(player, "button.fix"), lang.plain(player, "button.fix-hover"),
                    knownTarget ? "/pay " + target.getName() + " " : "/pay "));
            core.compat().platform().sendMessage(sender, line.append(Component.space()).append(accountButton(player)));
            return true;
        }
        core.compat().platform().sendMessage(sender, lang.component(player, "pay.sent", "amount", formatPoints(player, amount),
                "player", displayName(target)).append(Component.space()).append(accountButton(player)));
        return true;
    }

    /** Validates a payment before it is confirmed; returns the error text for the payer or null. */
    Component checkTransfer(Player payer, OfflinePlayer target, Long amount) {
        return checkTransfer(payer, target, amount, lang.language(payer));
    }

    /** {@link #checkTransfer(Player, OfflinePlayer, Long)} with texts in {@code language} (web requests). */
    Component checkTransfer(Player payer, OfflinePlayer target, Long amount, String language) {
        if (target == null || !hasAccount(target.getUniqueId())) return lang.component(language, "common.unknown-account");
        if (target.getUniqueId().equals(payer.getUniqueId())) return lang.component(language, "pay.self");
        if (amount == null || amount <= 0L) return lang.component(language, "pay.bad-amount");
        return null;
    }

    /** Moves XP from payer to target atomically; returns the error text for the payer or null on success. */
    Component transfer(Player payer, OfflinePlayer target, long amount) {
        return transfer(payer, target, amount, lang.language(payer));
    }

    /** {@link #transfer(Player, OfflinePlayer, long)} with the payer's texts in {@code language} (web requests). */
    Component transfer(Player payer, OfflinePlayer target, long amount, String language) {
        if (!payer.hasPermission("xpeconomy.pay")) return lang.component(language, "common.no-permission");
        Component invalid = checkTransfer(payer, target, amount, language);
        if (invalid != null) return invalid;
        synchronized (transactionLock) {
            UUID reservation;
            try { reservation = paymentDesk.reserve(payer.getUniqueId(), amount); }
            catch (java.io.IOException failure) {
                String reason = failure.getMessage();
                if (!"single".equals(reason) && !"daily".equals(reason)) reason = "storage";
                return lang.component(language, PaymentCommands.errorKey(reason));
            }
            long payerBefore = balance(payer), targetBefore = balance(target);
            boolean paid = false, outcomeKnown = false;
            paymentJournal.set(true);
            try {
                TransactionResult withdrawal = withdraw(payer, amount);
                if (!withdrawal.success()) {
                    outcomeKnown = true;
                    return Component.text(errorText(language, withdrawal.error()) + ".");
                }
                TransactionResult deposit = deposit(target, amount);
                if (!deposit.success()) {
                    TransactionResult refund = deposit(payer, amount);
                    outcomeKnown = refund.success();
                    if (!refund.success()) {
                        getLogger().severe("Payment refund failed for " + payer.getUniqueId() + "; manual balance review required.");
                        throw new IllegalStateException("Payment refund outcome requires manual review");
                    }
                    return lang.component(language, "pay.reverted", "error", errorText(language, deposit.error()));
                }
                paid = true; outcomeKnown = true;
            } finally {
                paymentJournal.remove();
                if (paid && transactionHistory != null) {
                    transactionHistory.record(reservation, payer.getUniqueId(), target.getUniqueId(), "pay", payerBefore, payerBefore - amount);
                    transactionHistory.record(reservation, target.getUniqueId(), payer.getUniqueId(), "pay", targetBefore, targetBefore + amount);
                }
                // An unexpected exception leaves the reservation in place: never release an uncertain debit.
                if (outcomeKnown) try { paymentDesk.settle(reservation, paid); }
                catch (java.io.IOException failure) {
                    getLogger().warning("Payment limit reservation " + reservation + " could not be settled; budget remains reserved.");
                }
            }
        }
        Player targetOnline = target.getPlayer();
        if (targetOnline != null) {
            Component line = lang.component(targetOnline, "pay.received", "player", payer.getName(),
                    "amount", formatPoints(targetOnline, amount)).append(Component.space()).append(accountButton(targetOnline));
            if (targetOnline.hasPermission("xpeconomy.pay"))
                line = line.append(Component.space()).append(core.message().chat().suggest(lang.plain(targetOnline, "button.reply"),
                        lang.plain(targetOnline, "button.reply-hover", "player", payer.getName()), "/pay " + payer.getName() + " "));
            core.compat().platform().sendMessage(targetOnline, line);
            if (client != null) client.paymentToast(targetOnline, payer.getName(), formatPoints(targetOnline, amount));
        } else noteAwayIncome(target, payer.getName(), amount);
        queueFlush();
        return null;
    }

    private boolean adminCommand(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadSettings();
            lang.send(sender, "admin.reloaded");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("info")) {
            lang.send(sender, "admin.info", "accounts", accounts.size(), "storage", storageText(sender));
            if (cheques != null) lang.send(sender, "cheque.admin-info", "count", cheques.outstanding(), "amount", formatPoints(sender, cheques.outstandingTotal()));
            if (banks != null) lang.send(sender, "bank.admin-info", "count", banks.ledger().size(), "amount", formatPoints(sender, banks.ledger().total()));
            if (standingOrders != null) lang.send(sender, "standing.admin-info", "count", standingOrders.book().size());
            if (funds != null) lang.send(sender, "fund.admin-info", "count", funds.book().size());
            if (termDeposits != null) lang.send(sender, "term.admin-info", "count", termDeposits.book().size(), "amount", formatPoints(sender, termDeposits.book().total()));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("history")) return adminHistory(sender, args);
        if (args.length != 3) return false;

        String action = args[0].toLowerCase(Locale.ROOT);
        OfflinePlayer target = resolveKnownPlayer(args[1]);
        Long amount = parsePoints(args[2]);
        if (target == null) {
            lang.send(sender, "admin.unknown-player");
            return true;
        }
        if (amount == null) {
            lang.send(sender, "admin.bad-amount");
            return true;
        }

        TransactionResult result = adminChange(action, target, amount);
        if (result == null) return false;
        if (!result.success()) {
            send(sender, errorText(lang.language(sender), result.error()) + ".", NamedTextColor.RED);
            return true;
        }

        lang.send(sender, "admin.new-balance", "player", displayName(target), "amount", formatPoints(sender, result.balance()));
        queueFlush();
        return true;
    }

    /** /economy admin history <player> [count]: the player's latest recorded bookings (default 10, at most 50). */
    private boolean adminHistory(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) { lang.send(sender, "admin.history-usage"); return true; }
        Integer count = args.length == 3 ? adminHistoryCount(args[2]) : Integer.valueOf(10);
        if (count == null) { lang.send(sender, "admin.history-usage"); return true; }
        OfflinePlayer target = resolveKnownPlayer(args[1]);
        if (target == null || !hasAccount(target.getUniqueId())) { lang.send(sender, "admin.unknown-player"); return true; }
        if (transactionHistory == null || !transactionHistory.readable()) { lang.send(sender, "history.unavailable"); return true; }
        var rows = transactionHistory.recent(target.getUniqueId(), count);
        String name = displayName(target);
        if (rows.isEmpty()) { lang.send(sender, "admin.history-empty", "player", name); return true; }
        lang.send(sender, "admin.history-header", "player", name, "count", rows.size());
        for (var b : rows) {
            OfflinePlayer other = b.counterparty() == null ? null : player(b.counterparty());
            lang.send(sender, "admin.history-row", "id", b.paymentId(), "time", java.time.Instant.ofEpochMilli(b.time()),
                    "cause", lang.component(sender, historyCause(b.cause())), "delta", b.after() - b.before(), "balance", b.after(),
                    "other", other == null ? "-" : displayName(other));
        }
        return true;
    }

    /** Row count for /economy admin history: a whole number from 1 to 50, else null. */
    static Integer adminHistoryCount(String text) {
        if (text == null || !text.matches("\\d{1,3}")) return null;
        int value = Integer.parseInt(text);
        return value < 1 || value > 50 ? null : value;
    }

    /** The lock every balance change runs under; banks hold it across their ledger write and the player booking. */
    Object transactionLock() {
        return transactionLock;
    }

    /** Player bookings for {@link BankTransfers}, recorded in the booking history as "bank". */
    BankTransfers.Wallet bankWallet() {
        return new BankTransfers.Wallet() {
            public TransactionResult take(UUID player, long amount) { return book(player(player), amount, false, "bank"); }
            public TransactionResult give(UUID player, long amount) { return book(player(player), amount, true, "bank"); }
            public PendingBookings.Intent intent(UUID player, long before, long after) { return pendingIntent(player, before, after); }
        };
    }

    private final AtomicLong intentSeq = new AtomicLong();

    /** Journal entry for {@link PendingBookings}: the balance revision right after the booking contains it. */
    PendingBookings.Intent pendingIntent(UUID player, long before, long after) {
        long seq = intentSeq.updateAndGet(last -> Math.max(last + 1, System.currentTimeMillis() * 1000));
        return new PendingBookings.Intent(player, before, after, seq, revision.get());
    }

    /**
     * Start: puts player balances of bank bookings and standing-order payments back that were committed in their files
     * but not yet in a balance save when the server stopped (crash). The journal is cleared by the next balance save.
     */
    private void replayPendingBookings() {
        List<PendingBookings.Intent> intents = new ArrayList<>();
        if (banks != null) intents.addAll(banks.ledger().pending());
        if (standingOrders != null) intents.addAll(standingOrders.book().pending());
        if (termDeposits != null) intents.addAll(termDeposits.book().pending());
        if (funds != null) intents.addAll(funds.book().pending());
        if (intents.isEmpty()) return;
        Map<UUID, Long> saved = new HashMap<>();
        accounts.forEach((uuid, account) -> saved.put(uuid, account.balance()));
        Map<UUID, Long> replayed = PendingBookings.replay(intents, saved);
        replayed.forEach((uuid, balance) -> {
            StoredAccount account = accounts.get(uuid);
            if (account != null) updateAccount(uuid, account.name(), balance, account.initialized(), false);
        });
        long current = revision.incrementAndGet();
        if (banks != null) banks.ledger().rebase(current);
        if (standingOrders != null) standingOrders.book().rebase(current);
        if (termDeposits != null) termDeposits.book().rebase(current);
        if (funds != null) funds.book().rebase(current);
        if (!replayed.isEmpty())
            getLogger().warning("Restored " + replayed.size() + " player balance(s) from bank or standing-order bookings that were not saved before the last stop.");
    }

    /** A balance save up to {@code savedRevision} finished: the journal entries it contains are no longer needed. */
    private void acknowledgeBookings(long savedRevision) {
        try {
            if (banks != null) banks.ledger().acknowledge(savedRevision);
            if (standingOrders != null) standingOrders.book().acknowledge(savedRevision);
            if (termDeposits != null) termDeposits.book().acknowledge(savedRevision);
            if (funds != null) funds.book().acknowledge(savedRevision);
        } catch (java.io.IOException | RuntimeException failure) {
            DebugMessages.mid("[XPVaultEconomy] Booking journal not cleared (kept, harmless): " + failure);
        }
    }

    /** Withdraw or deposit recorded in the booking history under {@code cause} instead of debit/credit. */
    private TransactionResult book(OfflinePlayer player, long amount, boolean credit, String cause) {
        synchronized (transactionLock) {
            long before = balance(player);
            TransactionResult result;
            paymentJournal.set(true);
            try { result = credit ? deposit(player, amount) : withdraw(player, amount); }
            finally { paymentJournal.remove(); }
            if (result.success() && transactionHistory != null)
                transactionHistory.record(UUID.randomUUID(), player.getUniqueId(), null, cause, before, result.balance());
            return result;
        }
    }

    /**
     * One run of a standing order: a player payment from a possibly offline payer, with the payer's personal limits,
     * refund when the payee's balance is full and both bookings in the history ("standing"). Returns a language key on
     * failure or null when paid.
     */
    String standingPayment(StandingOrderBook.Order order) {
        OfflinePlayer payer = player(order.payer()), payee = player(order.payee());
        if (!hasAccount(order.payee()) || !hasAccount(order.payer())) return "standing.error.account";
        Player online = payer.getPlayer();
        if (online != null && !online.hasPermission("xpeconomy.standing")) return "standing.error.permission";
        long amount = order.amount();
        synchronized (transactionLock) {
            long payerBefore = balance(payer), payeeBefore = balance(payee);
            if (payerBefore < amount) return "standing.error.insufficient";
            if (amount > maximumBalance - payeeBefore) return "standing.error.payee-full";
            UUID reservation;
            try { reservation = paymentDesk.reserve(order.payer(), amount); }
            catch (java.io.IOException failure) {
                String reason = failure.getMessage();
                return "single".equals(reason) || "daily".equals(reason) ? "standing.error.limit" : "standing.error.storage";
            }
            boolean paid = false, outcomeKnown = false;
            paymentJournal.set(true);
            try {
                TransactionResult withdrawal = withdraw(payer, amount);
                if (!withdrawal.success()) { outcomeKnown = true; return "standing.error.insufficient"; }
                TransactionResult deposit = deposit(payee, amount);
                if (!deposit.success()) {
                    TransactionResult refund = deposit(payer, amount);
                    outcomeKnown = refund.success();
                    if (!refund.success()) {
                        getLogger().severe("Standing order #" + order.id() + ": refund to " + order.payer() + " failed; manual balance review required.");
                        throw new IllegalStateException("Standing order refund outcome requires manual review");
                    }
                    return "standing.error.payee-full";
                }
                // Journaled in the settle write of the order, so a crash before the next balance save cannot lose the run.
                if (standingOrders != null) standingOrders.book().stage(List.of(
                        pendingIntent(order.payer(), payerBefore, withdrawal.balance()), pendingIntent(order.payee(), payeeBefore, deposit.balance())));
                paid = true; outcomeKnown = true;
            } finally {
                paymentJournal.remove();
                if (paid && transactionHistory != null) {
                    transactionHistory.record(reservation, order.payer(), order.payee(), "standing", payerBefore, payerBefore - amount);
                    transactionHistory.record(reservation, order.payee(), order.payer(), "standing", payeeBefore, payeeBefore + amount);
                }
                if (outcomeKnown) try { paymentDesk.settle(reservation, paid); }
                catch (java.io.IOException failure) {
                    getLogger().warning("Payment limit reservation " + reservation + " could not be settled; budget remains reserved.");
                }
            }
        }
        noteAwayIncome(payee, Optional.ofNullable(payer.getName()).orElse("?"), amount);
        return null;
    }

    /** Account dialog / player menu: the XP bank dialog. */
    void openBanks(Player player) {
        if (banks != null) banks.openList(player, null);
    }

    /** Player menu: add the clicked player to one of the viewer's banks. */
    void openAddBankMember(Player player, Player target) {
        if (banks != null) banks.openAddMember(player, target);
    }

    /** Whether the viewer owns a bank the target is not part of (player menu entry visibility). */
    boolean canAddBankMember(Player viewer, Player target) {
        return banks != null && banks.enabled() && banks.ledger().readable() && viewer.hasPermission("xpeconomy.bank")
                && !viewer.getUniqueId().equals(target.getUniqueId()) && hasAccount(target.getUniqueId())
                && !banks.ownedWithout(viewer.getUniqueId(), target.getUniqueId()).isEmpty();
    }

    boolean banksEnabled() { return banks != null && banks.enabled(); }

    boolean standingOrdersEnabled() { return standingOrders != null && standingOrders.enabled(); }

    boolean termDepositsEnabled() { return termDeposits != null && termDeposits.enabled(); }

    boolean fundsEnabled() { return funds != null && funds.enabled(); }

    void openFunds(Player player) { if (funds != null) funds.openList(player, null); }

    /** Player bookings for donation goals, recorded in the booking history as "fund". */
    BankTransfers.Wallet fundWallet() {
        return new BankTransfers.Wallet() {
            public TransactionResult take(UUID player, long amount) { return book(player(player), amount, false, "fund"); }
            public TransactionResult give(UUID player, long amount) { return book(player(player), amount, true, "fund"); }
            public PendingBookings.Intent intent(UUID player, long before, long after) { return pendingIntent(player, before, after); }
        };
    }

    /** Account dialog: the term deposit list. */
    void openTermDeposits(Player player) {
        if (termDeposits != null) termDeposits.openList(player, null);
    }

    /** Player bookings for term deposits, recorded in the booking history as "term". */
    BankTransfers.Wallet termWallet() {
        return new BankTransfers.Wallet() {
            public TransactionResult take(UUID player, long amount) { return book(player(player), amount, false, "term"); }
            public TransactionResult give(UUID player, long amount) { return book(player(player), amount, true, "term"); }
            public PendingBookings.Intent intent(UUID player, long before, long after) { return pendingIntent(player, before, after); }
        };
    }

    /** Account dialog: the standing order list. */
    void openStandingOrders(Player player) {
        if (standingOrders != null) standingOrders.openList(player, null);
    }

    /** Player menu: a new standing order with the recipient filled in. */
    void openStandingOrderTo(Player player, String target) {
        if (standingOrders != null) standingOrders.openCreate(player, target, null);
    }

    /** The Vault bank API, or null while it is switched off ({@code banks.vault-api}). */
    BankLedger vaultBanks() {
        return banks != null && banks.enabled() && getConfig().getBoolean("banks.vault-api", false) ? banks.ledger() : null;
    }

    /** give/take/set as in /economy admin; null for an unknown action. Saves soon after a successful change. */
    TransactionResult adminChange(String action, OfflinePlayer target, long amount) {
        TransactionResult result = switch (action) {
            case "give" -> deposit(target, amount);
            case "take" -> withdraw(target, amount);
            case "set" -> setBalance(target, amount);
            default -> null;
        };
        if (result != null && result.success() && Bukkit.isPrimaryThread()) queueFlush();
        return result;
    }

    int accountCount() {
        return accounts.size();
    }

    private String storageText(CommandSender viewer) {
        return storageText(lang.language(viewer));
    }

    String storageText(String language) {
        if (store instanceof SharedBalanceStore shared) return lang.plain(language, "storage.mysql", "table", shared.table());
        return lang.plain(language, "storage.yaml", "file", LocalBalanceStore.FOLDER + "/" + LocalBalanceStore.FILE);
    }

    /** Whole XP amount between 0 and the balance limit (the /pay and /economy admin syntax, also 1k/2.5k/1m); null otherwise. */
    Long parsePoints(String text) {
        return EconomySettings.parseCommandAmount(text, maximumBalance);
    }

    /** "[Account]" chat button opening the native account dialog, labelled in the viewer's language. */
    Component accountButton(CommandSender viewer) {
        return core.message().chat().button(lang.plain(viewer, "button.account"), lang.plain(viewer, "button.account-hover"),
                this::openAccount);
    }

    private void send(CommandSender sender, String message, NamedTextColor color) {
        core.compat().platform().sendMessage(sender, Component.text(message, color));
    }

    /** Argument suggestions for {@code /<command> <args>} (English names; the last argument is the typed prefix). */
    List<String> complete(CommandSender sender, String command, String[] args) {
        if (!ready) return List.of();
        if (command.equalsIgnoreCase("baltop"))
            return sender.hasPermission("xpeconomy.top") && args.length == 1 ? filter(pageSuggestions(accounts.size(), topPageSize()), args[0]) : List.of();
        if (command.equalsIgnoreCase("money") || command.equalsIgnoreCase("pay")) {
            EconomyCommandRoute direct = EconomyCommandRoute.parse(command, args);
            if (!sender.hasPermission(direct.permission())) return List.of();
            if (args.length == 1 && (direct.action().equals("pay") || sender.hasPermission("xpeconomy.balance.others")))
                return onlineNames(args[0]);
            if (args.length == 2 && direct.action().equals("pay")) return filter(List.of("10", "100", "1000"), args[1]);
            return List.of();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("top") || args[0].equalsIgnoreCase("rangliste")))
            return sender.hasPermission("xpeconomy.top") ? filter(pageSuggestions(accounts.size(), topPageSize()), args[1]) : List.of();
        return EconomyTab.complete(sender, args, webAvailable(),
                lang == null ? null : lang.plain(sender, "command.account"),
                () -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),
                () -> sender instanceof Player player && paymentDesk != null && paymentDesk.readable()
                        ? paymentDesk.openIncoming(player.getUniqueId()).stream().map(UUID::toString).toList() : List.of(),
                () -> banks == null ? List.of() : (sender instanceof Player player ? banks.ledger().of(player.getUniqueId())
                        : banks.ledger().all()).stream().map(BankLedger.Bank::name).toList(),
                () -> sender instanceof Player player && standingOrders != null
                        ? standingOrders.book().of(player.getUniqueId()).stream().map(o -> Integer.toString(o.id())).toList() : List.of(),
                () -> sender instanceof Player player && paymentDesk != null && paymentDesk.readable()
                        ? paymentDesk.openOutgoing(player.getUniqueId()).stream().map(UUID::toString).toList() : List.of());
    }

    /** Leaderboard pages that actually exist (at least page 1), at most the first nine. */
    static List<String> pageSuggestions(int accounts, int pageSize) {
        int pages = Math.max(1, (accounts + Math.max(1, pageSize) - 1) / Math.max(1, pageSize));
        return java.util.stream.IntStream.rangeClosed(1, Math.min(9, pages)).mapToObj(Integer::toString).toList();
    }

    private List<String> onlineNames(String prefix) {
        return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), prefix);
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    static record RewardGrant(long payout, double fractionalXp, long completedIntervals) {
    }
}
