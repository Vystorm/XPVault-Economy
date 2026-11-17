package local.xpeconomytest;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.main;
import Vystorm.vystorm_core._coreMethods.playerHandler.DefaultPlayerHandler;
import Vystorm.vystorm_core._coreMethods.playerHandler.PlayerHandlerInterface;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public final class XPVaultEconomyTest extends JavaPlugin {
    private int checks;
    @Override
    public void onEnable() {
        if (!Boolean.getBoolean("vystorm.xp.integration") || Bukkit.getPort() != 25580
                || !Bukkit.getIp().equals("127.0.0.1")) {
            getLogger().severe("Nur für isolierte Tests mit -Dvystorm.xp.integration=true auf 127.0.0.1:25580");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        API core = main.getApi();
        PlayerHandlerInterface original = core.player();
        try {
            Economy legacy = require(Bukkit.getServicesManager().load(Economy.class), "Legacy-Vault-Provider fehlt");
            net.milkbowl.vault2.economy.Economy modern = require(
                    Bukkit.getServicesManager().load(net.milkbowl.vault2.economy.Economy.class),
                    "Vault-2-Provider fehlt");

            UUID uuid = UUID.nameUUIDFromBytes("XPVaultEconomy-Integrationstest".getBytes(StandardCharsets.UTF_8));
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            if (!legacy.hasAccount(player)) legacy.createPlayerAccount(player);
            long initial = (long) legacy.getBalance(player);

            check(legacy.depositPlayer(player, 100).transactionSuccess(), "Legacy deposit 100");
            check(legacy.withdrawPlayer(player, 40).transactionSuccess(), "Legacy withdraw 40");
            check((long) legacy.getBalance(player) == initial + 60, "Legacy balance initial+60");

            EconomyResponse fractional = legacy.depositPlayer(player, 0.5);
            check(!fractional.transactionSuccess(), "Bruchteile werden abgelehnt");

            check(modern.getBalance("integration-test", uuid).longValueExact() == initial + 60,
                    "Vault 2 liest Legacy-Aenderung");
            check(modern.deposit("integration-test", uuid, BigDecimal.TEN).transactionSuccess(),
                    "Vault 2 deposit 10");
            check(modern.withdraw("integration-test", uuid, BigDecimal.valueOf(70)).transactionSuccess(),
                    "Vault 2 Ruecksetzung");
            check((long) legacy.getBalance(player) == initial, "Gemeinsamer Endstand wiederhergestellt");

            check(core.vault().isEconomyAvailable(), "Core erkennt den spät registrierten XP-Anbieter");
            check(core.vault().deposit(player, 9).successful(), "Core Vault zahlt über XPVaultEconomy ein");
            check((long) legacy.getBalance(player) == initial + 9, "Core und Legacy teilen dasselbe Konto");
            check(core.vault().withdraw(player, 9).successful(), "Core Vault bucht ohne Rekursion ab");
            check((long) legacy.getBalance(player) == initial, "Core Vault stellt den Ausgangsstand wieder her");

            AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger(), assigned = new AtomicInteger(-1);
            core.overridePlayerHandler(new DefaultPlayerHandler() {
                @Override public int experience(Player target) {
                    reads.incrementAndGet();
                    return super.experience(target);
                }
                @Override public void setExperience(Player target, int amount) {
                    writes.incrementAndGet();
                    super.setExperience(target, amount);
                }
            });

            UUID importUuid = UUID.nameUUIDFromBytes("XPVaultEconomy-Erstimport".getBytes(StandardCharsets.UTF_8));
            OfflinePlayer offlineImport = Bukkit.getOfflinePlayer(importUuid);
            if (!legacy.hasAccount(offlineImport)) legacy.createPlayerAccount(offlineImport);
            check(legacy.depositPlayer(offlineImport, 25).transactionSuccess(), "Offline-Einzahlung vor Erstbeitritt");

            // Current join handling reads per-player preferences. Supply a real, isolated
            // Paper PDC instead of the proxy's null fallback for reference return types.
            var preferences = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER)
                    .getItemMeta().getPersistentDataContainer();
            Player simulatedJoin = (Player) Proxy.newProxyInstance(
                    Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "getUniqueId" -> importUuid;
                        case "getName" -> "ErstimportTest";
                        case "getPersistentDataContainer" -> preferences;
                        case "calculateTotalExperiencePoints" -> 1234;
                        case "setExperienceLevelAndProgress" -> { assigned.set((int) arguments[0]); yield null; }
                        case "isOnline" -> true;
                        case "hashCode" -> importUuid.hashCode();
                        case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                        case "toString" -> "SimulatedPlayer[ErstimportTest]";
                        default -> primitiveDefault(method.getReturnType());
                    });
            Plugin economyPlugin = require(Bukkit.getPluginManager().getPlugin("XPVaultEconomy"),
                    "XPVaultEconomy-Instanz fehlt");
            Method handleJoin = economyPlugin.getClass().getDeclaredMethod("handleJoin", Player.class);
            handleJoin.setAccessible(true);
            handleJoin.invoke(economyPlugin, simulatedJoin);
            check((long) legacy.getBalance(simulatedJoin) == 1259,
                    "Erstimport uebernimmt 1234 native XP plus 25 Offline-Einzahlung");
            check(reads.get() > 0 && writes.get() == 1 && assigned.get() == 1259,
                    "XPVaultEconomy liest und setzt XP über den austauschbaren Core-Handler");
            core.overridePlayerHandler(new DefaultPlayerHandler() {
                @Override public void setExperience(Player target, int amount) { assigned.set(amount + 1); }
            });
            handleJoin.invoke(economyPlugin, simulatedJoin);
            check(assigned.get() == 1260, "Späterer Core-Handlerwechsel wird übernommen");

            core.debug().flush(java.time.Duration.ofSeconds(5));

            getLogger().info("INTEGRATIONSTEST BESTANDEN: checks=" + checks
                    + "; Core API + Legacy Vault + Vault 2, Transaktionen, Ganzzahlpruefung, Erstimport und Handlerwechsel.");
        } catch (Throwable throwable) {
            getLogger().severe("INTEGRATIONSTEST FEHLGESCHLAGEN: " + throwable.getMessage());
            if (throwable instanceof RuntimeException runtime) throw runtime;
            throw new RuntimeException(throwable);
        } finally {
            core.overridePlayerHandler(original);
            Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
        }
    }

    private static <T> T require(T value, String message) {
        if (value == null) throw new IllegalStateException(message);
        return value;
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
        checks++;
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        return 0.0D;
    }
}
