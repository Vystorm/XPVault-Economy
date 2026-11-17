package local.xpeconomy;

import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class XPEconomyProviderV2 implements net.milkbowl.vault2.economy.Economy {
    private static final String CURRENCY = "xp";
    private final XPVaultEconomy plugin;

    XPEconomyProviderV2(XPVaultEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public String getName() {
        return "XPVaultEconomy";
    }

    @Override
    public boolean hasSharedAccountSupport() {
        return false;
    }

    @Override
    public boolean hasMultiCurrencySupport() {
        return false;
    }

    @Override
    public int fractionalDigits(String pluginName) {
        return 0;
    }

    @Override
    public String format(BigDecimal amount) {
        return plugin.formatPointsForApi(toDisplayLong(amount));
    }

    @Override
    public String format(String pluginName, BigDecimal amount) {
        return format(amount);
    }

    @Override
    public String format(BigDecimal amount, String currency) {
        return format(amount);
    }

    @Override
    public String format(String pluginName, BigDecimal amount, String currency) {
        return format(amount);
    }

    @Override
    public boolean hasCurrency(String currency) {
        return currency != null && CURRENCY.equalsIgnoreCase(currency);
    }

    @Override
    public String getDefaultCurrency(String pluginName) {
        return CURRENCY;
    }

    @Override
    public String defaultCurrencyNamePlural(String pluginName) {
        return plugin.currencyPlural();
    }

    @Override
    public String defaultCurrencyNameSingular(String pluginName) {
        return plugin.currencySingular();
    }

    @Override
    public Collection<String> currencies() {
        return List.of(CURRENCY);
    }

    @Override
    public boolean createAccount(UUID uuid, String name) {
        return plugin.createAccount(uuid, name);
    }

    @Override
    public boolean createAccount(UUID uuid, String name, boolean player) {
        return createAccount(uuid, name);
    }

    @Override
    public boolean createAccount(UUID uuid, String name, String world) {
        return createAccount(uuid, name);
    }

    @Override
    public boolean createAccount(UUID uuid, String name, String world, boolean player) {
        return createAccount(uuid, name);
    }

    @Override
    public Map<UUID, String> getUUIDNameMap() {
        return plugin.accountNames();
    }

    @Override
    public Optional<String> getAccountName(UUID uuid) {
        return plugin.accountName(uuid);
    }

    @Override
    public boolean hasAccount(UUID uuid) {
        return plugin.hasAccount(uuid);
    }

    @Override
    public boolean hasAccount(UUID uuid, String world) {
        return hasAccount(uuid);
    }

    @Override
    public boolean renameAccount(UUID uuid, String name) {
        return plugin.renameAccount(uuid, name);
    }

    @Override
    public boolean renameAccount(String pluginName, UUID uuid, String name) {
        return renameAccount(uuid, name);
    }

    @Override
    public boolean deleteAccount(String pluginName, UUID uuid) {
        return false;
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID uuid, String currency) {
        return hasAccount(uuid) && hasCurrency(currency);
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID uuid, String world, String currency) {
        return accountSupportsCurrency(pluginName, uuid, currency);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID uuid) {
        return BigDecimal.valueOf(plugin.balance(Bukkit.getOfflinePlayer(uuid)));
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID uuid, String world) {
        return getBalance(pluginName, uuid);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID uuid, String world, String currency) {
        return hasCurrency(currency) ? getBalance(pluginName, uuid) : BigDecimal.ZERO;
    }

    @Override
    public boolean has(String pluginName, UUID uuid, BigDecimal amount) {
        Long points = validateAmount(amount);
        return points != null && plugin.balance(Bukkit.getOfflinePlayer(uuid)) >= points;
    }

    @Override
    public boolean has(String pluginName, UUID uuid, String world, BigDecimal amount) {
        return has(pluginName, uuid, amount);
    }

    @Override
    public boolean has(String pluginName, UUID uuid, String world, String currency, BigDecimal amount) {
        return hasCurrency(currency) && has(pluginName, uuid, amount);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID uuid, BigDecimal amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        Long points = validateAmount(amount);
        if (points == null) return invalidAmount(player);
        return response(plugin.withdraw(player, points));
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID uuid, String world, BigDecimal amount) {
        return withdraw(pluginName, uuid, amount);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID uuid, String world, String currency, BigDecimal amount) {
        return hasCurrency(currency) ? withdraw(pluginName, uuid, amount) : unsupportedCurrency(uuid);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID uuid, BigDecimal amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        Long points = validateAmount(amount);
        if (points == null) return invalidAmount(player);
        return response(plugin.deposit(player, points));
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID uuid, String world, BigDecimal amount) {
        return deposit(pluginName, uuid, amount);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID uuid, String world, String currency, BigDecimal amount) {
        return hasCurrency(currency) ? deposit(pluginName, uuid, amount) : unsupportedCurrency(uuid);
    }

    private Long validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() < 0 || amount.compareTo(BigDecimal.valueOf(plugin.maxBalance())) > 0)
            return null;
        try {
            return amount.longValueExact();
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private long toDisplayLong(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return 0L;
        return amount.min(BigDecimal.valueOf(plugin.maxBalance())).longValue();
    }

    private EconomyResponse response(TransactionResult result) {
        return new EconomyResponse(BigDecimal.valueOf(result.amount()), BigDecimal.valueOf(result.balance()),
                result.success() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
                result.success() ? "" : plugin.errorText(plugin.apiLanguage(), result.error()));
    }

    private EconomyResponse invalidAmount(OfflinePlayer player) {
        return new EconomyResponse(BigDecimal.ZERO, BigDecimal.valueOf(plugin.balance(player)),
                EconomyResponse.ResponseType.FAILURE, plugin.apiText("vault.invalid-amount"));
    }

    private EconomyResponse unsupportedCurrency(UUID uuid) {
        return new EconomyResponse(BigDecimal.ZERO,
                BigDecimal.valueOf(plugin.balance(Bukkit.getOfflinePlayer(uuid))),
                EconomyResponse.ResponseType.NOT_IMPLEMENTED, plugin.apiText("vault.unsupported-currency"));
    }

    @Override
    public boolean createSharedAccount(String pluginName, UUID uuid, String name, UUID owner) {
        return false;
    }

    @Override
    public boolean isAccountOwner(String pluginName, UUID account, UUID player) {
        return account.equals(player) && hasAccount(account);
    }

    @Override
    public boolean setOwner(String pluginName, UUID account, UUID player) {
        return false;
    }

    @Override
    public boolean isAccountMember(String pluginName, UUID account, UUID player) {
        return false;
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID account, UUID player) {
        return false;
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID account, UUID player, AccountPermission... permissions) {
        return false;
    }

    @Override
    public boolean removeAccountMember(String pluginName, UUID account, UUID player) {
        return false;
    }

    @Override
    public boolean hasAccountPermission(String pluginName, UUID account, UUID player, AccountPermission permission) {
        return isAccountOwner(pluginName, account, player);
    }

    @Override
    public boolean updateAccountPermission(String pluginName, UUID account, UUID player,
                                           AccountPermission permission, boolean value) {
        return false;
    }
}
