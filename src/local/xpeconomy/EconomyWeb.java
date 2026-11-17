package local.xpeconomy;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.web.WebException;
import Vystorm.vystorm_core.web.WebModule;
import Vystorm.vystorm_core.web.WebOpenResult;
import Vystorm.vystorm_core.web.WebRequest;
import Vystorm.vystorm_core.web.WebResponse;
import Vystorm.vystorm_core.web.WebRoute;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Optional web page {@code economy} (Vystorm Core 0.24.0+, capability {@code web-open}): own account with live
 * balance and online bonus, payments, leaderboard and admin tools in the browser and the client overlay. Every route
 * runs the same plugin methods as the commands and the account dialog on the server thread; the page only shows and
 * asks.
 */
final class EconomyWeb {
    static final String MODULE = "economy";
    private static final String PAY = "xpeconomy.pay";
    private static final String TOP = "xpeconomy.top";
    private static final String ADMIN = "xpeconomy.admin";
    private static final String OTHERS = "xpeconomy.balance.others";
    private static final long WAIT_MILLIS = 8_000L;

    /** Last permissions measured while online (the page shows tabs from them while the player is offline). */
    private record Permissions(boolean pay, boolean top, boolean admin, boolean others) {
        /** Offline without a measured value: the plugin.yml defaults (OP-dependent), as Core decides offline. */
        static Permissions defaults(OfflinePlayer player) {
            boolean op = player.isOp();
            return new Permissions(byDefault(PAY, op), byDefault(TOP, op), byDefault(ADMIN, op), byDefault(OTHERS, op));
        }

        private static boolean byDefault(String node, boolean op) {
            var permission = Bukkit.getPluginManager().getPermission(node);
            return (permission == null ? org.bukkit.permissions.Permission.DEFAULT_PERMISSION : permission.getDefault()).getValue(op);
        }

        static Permissions of(Player player) {
            return new Permissions(player.hasPermission(PAY), player.hasPermission(TOP), player.hasPermission(ADMIN),
                    player.hasPermission(OTHERS));
        }
    }

    private final XPVaultEconomy plugin;
    private final API core;
    private final Lang lang;
    private final RequestDedupe dedupe = new RequestDedupe(120_000L, 512, WAIT_MILLIS, System::currentTimeMillis);
    private final Map<UUID, Permissions> permissions = new ConcurrentHashMap<>();

    EconomyWeb(XPVaultEconomy plugin, API core) {
        this.core = core;
        this.plugin = plugin;
        this.lang = plugin.lang();
    }

    void register() {
        core.web().register(plugin, WebModule.builder(MODULE, lang.ref("web.module.title"))
                .description(lang.ref("web.module.description"))
                .permission("xpeconomy.balance")
                .snapshot(context -> Map.of("account", context.sync(() -> account(context.playerId()))))
                .route(WebRoute.get("/meta", this::meta).rateLimit(60).build())
                .route(WebRoute.get("/account", request -> WebResponse.json(request.sync(() -> account(request.playerId()))))
                        .rateLimit(120).build())
                .route(WebRoute.get("/top", this::top).permission(TOP).rateLimit(60).build())
                .route(WebRoute.post("/pay", this::pay).permission(PAY).requiresOnline().rateLimit(30).maxBody(512).build())
                .route(WebRoute.get("/lookup", this::lookup).permission(OTHERS).rateLimit(60).build())
                .route(WebRoute.get("/admin/info", this::adminInfo).permission(ADMIN).rateLimit(60).build())
                .route(WebRoute.post("/admin/change", this::adminChange).permission(ADMIN).rateLimit(60).maxBody(512).build())
                .build());
    }

    void unregister() {
        try {
            core.web().unregister(plugin, MODULE);
        } catch (RuntimeException ignored) {
            // Core cleans up on disable anyway.
        }
    }

    /** Whether the web platform runs and the page can be opened now. */
    boolean available() {
        try {
            return core.web().available() && core.web().registered(MODULE);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** Overlay (client mod) or chat link to the page ({@code subPath} "", "pay", "top", "admin"). Server thread. */
    WebOpenResult open(Player player, String subPath) {
        return core.web().openOrLink(player, MODULE, subPath, null);
    }

    /** Sends the current account to the player's open pages (server thread; cheap without a connection). */
    void push(UUID player) {
        try {
            Player online = Bukkit.getPlayer(player);
            if (online != null) permissions.put(player, Permissions.of(online));
            core.web().push(player, MODULE, "account", account(player));
        } catch (RuntimeException failure) {
            // no live channel or web off: nothing to update
        }
    }

    /** Server settings changed (currency, bonus rules, page size): open pages reload their data. */
    void configChanged() {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("at", System.currentTimeMillis());
            core.web().broadcast(MODULE, "config", json);
        } catch (RuntimeException ignored) {
            // web off
        }
    }

    // ---- routes ----

    /** Account JSON (server thread): online players with their live XP, offline from the stored account. */
    private JsonObject account(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        OfflinePlayer player = online != null ? online : Bukkit.getOfflinePlayer(uuid);
        boolean known = plugin.hasAccount(uuid);
        long balance = known || online != null ? plugin.balance(player) : 0L;
        String name = online != null ? online.getName() : plugin.accountName(uuid).orElse("");
        return EconomyWebJson.account(name, known, online != null, balance, plugin.maxBalance(),
                plugin.rewardView(uuid, online != null), online == null ? null : PersonalEconomyPrefs.goal(core.settings().prefs(), plugin, online));
    }

    private WebResponse meta(WebRequest request) {
        String language = request.language();
        JsonObject json = request.sync(() -> {
            Player player = Bukkit.getPlayer(request.playerId());
            Permissions perms = player != null ? Permissions.of(player)
                    : java.util.Objects.requireNonNullElseGet(permissions.get(request.playerId()),
                            () -> Permissions.defaults(Bukkit.getOfflinePlayer(request.playerId())));
            if (player != null) permissions.put(request.playerId(), perms);
            JsonObject meta = new JsonObject();
            meta.addProperty("online", player != null);
            JsonObject currency = new JsonObject();
            currency.addProperty("singular", plugin.currencyName(language, true));
            currency.addProperty("plural", plugin.currencyName(language, false));
            meta.add("currency", currency);
            meta.addProperty("max", plugin.maxBalance());
            meta.addProperty("pageSize", plugin.topPageSize());
            JsonObject rewards = new JsonObject();
            rewards.addProperty("enabled", plugin.onlineRewardsEnabled());
            rewards.addProperty("baseXp", plugin.onlineRewardBaseXp());
            rewards.addProperty("growth", plugin.onlineRewardGrowth());
            rewards.addProperty("intervalSeconds", Math.max(1L, plugin.onlineRewardIntervalMillis() / 1000L));
            rewards.addProperty("graceMinutes", plugin.onlineRewardGraceMillis() / 60_000L);
            meta.add("rewards", rewards);
            JsonObject can = new JsonObject();
            can.addProperty("pay", perms.pay());
            can.addProperty("top", perms.top());
            can.addProperty("admin", perms.admin());
            can.addProperty("others", perms.others());
            meta.add("can", can);
            meta.addProperty("settingsPage", true);
            return meta;
        });
        return WebResponse.json(json);
    }

    /** /baltop as JSON: same cached ranking, same hidden name prefixes, same page size. */
    private WebResponse top(WebRequest request) {
        String language = request.language();
        int page = 1;
        String raw = request.query("page").orElse(null);
        if (raw != null) {
            Integer parsed = EconomyCommandRoute.parsePage(raw);
            if (parsed == null) throw WebException.badRequest(lang.plain(language, "top.bad-page"));
            page = parsed;
        }
        CompletableFuture<BalanceRanking> future = new CompletableFuture<>();
        request.sync(() -> {
            plugin.requestLeaderboard(future::complete);
            return null;
        });
        BalanceRanking ranking = await(future, language);
        if (ranking == null) throw new WebException(503, lang.plain(language, "top.unavailable"));
        return WebResponse.json(EconomyWebJson.top(ranking, page, plugin.topPageSize(), request.playerId(),
                System.currentTimeMillis()));
    }

    private BalanceRanking await(CompletableFuture<BalanceRanking> future, String language) {
        try {
            return future.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException failure) {
            throw new WebException(503, lang.plain(language, "top.unavailable"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WebException(503, lang.plain(language, "top.unavailable"));
        }
    }

    /** Same checks, same transfer and the same texts as /pay; the XP is taken from the online payer. */
    private WebResponse pay(WebRequest request) {
        String language = request.language();
        JsonElement body = request.body();
        String target = EconomyWebJson.text(body, "target", 64);
        String amountText = EconomyWebJson.text(body, "amount", 24);
        String requestId = requestId(body, language);
        return deduped(request, requestId, language, () -> {
            Player payer = Bukkit.getPlayer(request.playerId());
            if (payer == null) throw new WebException(409, lang.plain(language, "web.error.offline"));
            if (!payer.hasPermission(PAY)) throw WebException.forbidden(lang.plain(language, "common.no-permission"));
            OfflinePlayer recipient = plugin.resolveKnownPlayer(target);
            Long amount = amountText == null ? null : plugin.parsePoints(amountText);
            Component problem = plugin.checkTransfer(payer, recipient, amount, language);
            if (problem == null) problem = plugin.transfer(payer, recipient, amount, language);
            if (problem != null) throw WebException.conflict(plain(problem));
            JsonObject json = new JsonObject();
            json.addProperty("message", lang.plain(language, "pay.sent", "amount", plugin.formatPoints(language, amount),
                    "player", plugin.displayName(recipient)));
            json.add("account", account(payer.getUniqueId()));
            return WebResponse.json(json);
        });
    }

    /** /money &lt;player&gt; for the admin tab (permission xpeconomy.balance.others, as in game). */
    private WebResponse lookup(WebRequest request) {
        String language = request.language();
        String name = request.query("name").map(String::strip).orElse("");
        if (name.isEmpty() || name.length() > 64) throw WebException.notFound(lang.plain(language, "common.unknown-account"));
        return WebResponse.json(request.sync(() -> {
            OfflinePlayer target = plugin.resolveKnownPlayer(name);
            if (target == null || !plugin.hasAccount(target.getUniqueId()))
                throw WebException.notFound(lang.plain(language, "common.unknown-account"));
            Player viewer = Bukkit.getPlayer(request.playerId());
            if (!plugin.balanceVisible(request.playerId(), viewer != null && viewer.hasPermission(ADMIN), target.getUniqueId()))
                throw WebException.notFound(lang.plain(language, "privacy.unavailable"));
            return target(target);
        }));
    }

    private JsonObject target(OfflinePlayer target) {
        JsonObject json = new JsonObject();
        long balance = plugin.balance(target);
        json.addProperty("name", plugin.displayName(target));
        json.addProperty("balance", balance);
        json.addProperty("level", BalanceRanking.levelForPoints(balance));
        json.addProperty("online", Bukkit.getPlayer(target.getUniqueId()) != null);
        return json;
    }

    private WebResponse adminInfo(WebRequest request) {
        String language = request.language();
        return WebResponse.json(request.sync(() -> {
            requireAdminIfOnline(request, language);
            JsonObject json = new JsonObject();
            json.addProperty("accounts", plugin.accountCount());
            json.addProperty("storage", plugin.storageText(language));
            json.addProperty("text", lang.plain(language, "admin.info", "accounts", plugin.accountCount(),
                    "storage", plugin.storageText(language)));
            return json;
        }));
    }

    /** /economy admin give|take|set &lt;player&gt; &lt;XP points&gt; with the same checks and texts. */
    private WebResponse adminChange(WebRequest request) {
        String language = request.language();
        JsonElement body = request.body();
        String rawAction = EconomyWebJson.text(body, "action", 16);
        String action = EconomyWebJson.adminAction(rawAction);
        if (action == null) throw WebException.badRequest(lang.plain(language, "web.error.bad-action"));
        String name = EconomyWebJson.text(body, "target", 64);
        String amountText = EconomyWebJson.text(body, "amount", 24);
        String requestId = requestId(body, language);
        return deduped(request, requestId, language, () -> {
            requireAdminIfOnline(request, language);
            OfflinePlayer target = plugin.resolveKnownPlayer(name);
            if (target == null) throw WebException.notFound(lang.plain(language, "admin.unknown-player"));
            Long amount = amountText == null ? null : plugin.parsePoints(amountText);
            if (amount == null) throw WebException.badRequest(lang.plain(language, "admin.bad-amount"));
            TransactionResult result = plugin.adminChange(action, target, amount);
            if (!result.success()) throw WebException.conflict(plugin.errorText(language, result.error()) + ".");
            DebugMessages.mid("[XPVaultEconomy web] " + request.playerName() + ": " + action + " " + amount + " "
                    + plugin.displayName(target) + " -> " + result.balance());
            JsonObject json = new JsonObject();
            json.addProperty("message", lang.plain(language, "admin.new-balance", "player", plugin.displayName(target),
                    "amount", plugin.formatPoints(language, result.balance())));
            json.add("target", target(target));
            return WebResponse.json(json);
        });
    }

    /** Core checked the permission already (last online value while offline); online players are checked live. */
    private void requireAdminIfOnline(WebRequest request, String language) {
        Player player = Bukkit.getPlayer(request.playerId());
        if (player != null && !player.hasPermission(ADMIN))
            throw WebException.forbidden(lang.plain(language, "common.no-permission"));
    }

    private String requestId(JsonElement body, String language) {
        if (body == null || !body.isJsonObject() || !body.getAsJsonObject().has("requestId")) return null;
        String id = EconomyWebJson.text(body, "requestId", 64);
        if (!RequestDedupe.validId(id)) throw WebException.badRequest(lang.plain(language, "web.error.bad-request"));
        return id;
    }

    /** Runs {@code task} on the server thread, at most once per request id (see {@link RequestDedupe}). */
    private WebResponse deduped(WebRequest request, String requestId, String language,
                                java.util.concurrent.Callable<WebResponse> task) {
        try {
            return dedupe.run(request.playerId(), requestId, request::sync, task);
        } catch (RequestDedupe.DuplicateBusyException busy) {
            throw new WebException(409, lang.plain(language, "web.error.busy"));
        }
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
