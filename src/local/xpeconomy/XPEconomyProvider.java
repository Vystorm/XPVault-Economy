package local.xpeconomy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.List;

@SuppressWarnings("deprecation")
final class XPEconomyProvider implements Economy {
    private final XPVaultEconomy plugin;

    XPEconomyProvider(XPVaultEconomy plugin) {
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
    public boolean hasBankSupport() {
        return plugin.vaultBanks() != null;
    }

    @Override
    public int fractionalDigits() {
        return 0;
    }

    @Override
    public String format(double amount) {
        return plugin.formatPointsForApi(Math.max(0L, (long) Math.floor(amount)));
    }

    @Override
    public String currencyNamePlural() {
        return plugin.currencyPlural();
    }

    @Override
    public String currencyNameSingular() {
        return plugin.currencySingular();
    }

    @Override
    public boolean hasAccount(String playerName) {
        OfflinePlayer player = plugin.resolveKnownPlayer(playerName);
        return player != null && hasAccount(player);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return plugin.hasAccount(player.getUniqueId());
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        OfflinePlayer player = plugin.resolveKnownPlayer(playerName);
        return player == null ? 0.0 : getBalance(player);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return plugin.balance(player);
    }

    @Override
    public double getBalance(String playerName, String worldName) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String worldName) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        OfflinePlayer player = plugin.resolveKnownPlayer(playerName);
        return player != null && has(player, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        Long points = validateAmount(amount);
        return points != null && plugin.balance(player) >= points;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        OfflinePlayer player = plugin.resolveKnownPlayer(playerName);
        return player == null ? unknownPlayer() : withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        Long points = validateAmount(amount);
        if (points == null) return invalidAmount(player, amount);
        return response(plugin.withdraw(player, points));
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        OfflinePlayer player = plugin.resolveKnownPlayer(playerName);
        return player == null ? unknownPlayer() : depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        Long points = validateAmount(amount);
        if (points == null) return invalidAmount(player, amount);
        return response(plugin.deposit(player, points));
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    private Long validateAmount(double amount) {
        if (!Double.isFinite(amount) || amount < 0.0 || amount != Math.rint(amount) || amount > plugin.maxBalance())
            return null;
        return (long) amount;
    }

    private EconomyResponse invalidAmount(OfflinePlayer player, double amount) {
        return new EconomyResponse(0.0, plugin.balance(player), EconomyResponse.ResponseType.FAILURE,
                plugin.apiText("vault.invalid-amount-value", "amount", amount));
    }

    private EconomyResponse unknownPlayer() {
        return new EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.FAILURE,
                plugin.apiText("vault.unknown-account"));
    }

    private EconomyResponse response(TransactionResult result) {
        return new EconomyResponse(result.amount(), result.balance(),
                result.success() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
                result.success() ? "" : plugin.errorText(plugin.apiLanguage(), result.error()));
    }

    private EconomyResponse unsupportedBank() {
        return new EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                plugin.apiText("vault.no-banks"));
    }

    // Vault bank API on the XP banks of /economy bank, only with banks.vault-api: true. Bank changes through the API
    // create or remove XP like depositPlayer/withdrawPlayer do; non-empty banks are never deleted through it.

    private EconomyResponse bankFailure(String key, long balance) {
        return new EconomyResponse(0.0, balance, EconomyResponse.ResponseType.FAILURE, plugin.apiText(key));
    }

    private EconomyResponse bankResult(String name, java.util.function.Function<BankLedger, EconomyResponse> action) {
        BankLedger ledger = plugin.vaultBanks();
        if (ledger == null) return unsupportedBank();
        if (!ledger.readable()) return bankFailure("vault.bank-storage", 0);
        if (ledger.get(name).isEmpty()) return bankFailure("vault.bank-unknown", 0);
        return action.apply(ledger);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        OfflinePlayer owner = plugin.resolveKnownPlayer(player);
        return owner == null ? unknownPlayer() : createBank(name, owner);
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        BankLedger ledger = plugin.vaultBanks();
        if (ledger == null) return unsupportedBank();
        try {
            ledger.create(name, player.getUniqueId(), System.currentTimeMillis(), Integer.MAX_VALUE);
            return new EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.SUCCESS, "");
        } catch (java.io.IOException failure) {
            String code = BankTransfers.code(failure);
            return bankFailure(code.equals("name") || code.equals("taken") ? "vault.bank-name" : "vault.bank-storage", 0);
        }
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return bankResult(name, ledger -> {
            synchronized (plugin.transactionLock()) {
                long balance = ledger.get(name).map(BankLedger.Bank::balance).orElse(0L);
                if (balance > 0) return bankFailure("vault.bank-not-empty", balance);
                try { ledger.remove(name); }
                catch (java.io.IOException failure) { return bankFailure("vault.bank-storage", balance); }
                return new EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.SUCCESS, "");
            }
        });
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return bankResult(name, ledger -> {
            long balance = ledger.get(name).map(BankLedger.Bank::balance).orElse(0L);
            return new EconomyResponse(0.0, balance, EconomyResponse.ResponseType.SUCCESS, "");
        });
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return bankResult(name, ledger -> {
            long balance = ledger.get(name).map(BankLedger.Bank::balance).orElse(0L);
            Long points = validateAmount(amount);
            if (points == null) return bankFailure("vault.bank-amount", balance);
            return balance >= points ? new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, "")
                    : bankFailure("vault.bank-insufficient", balance);
        });
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return bankChange(name, amount, false);
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return bankChange(name, amount, true);
    }

    private EconomyResponse bankChange(String name, double amount, boolean deposit) {
        return bankResult(name, ledger -> {
            synchronized (plugin.transactionLock()) {
                long balance = ledger.get(name).map(BankLedger.Bank::balance).orElse(0L);
                Long points = validateAmount(amount);
                if (points == null) return bankFailure("vault.bank-amount", balance);
                if (points == 0) return new EconomyResponse(0.0, balance, EconomyResponse.ResponseType.SUCCESS, "");
                try {
                    ledger.change(name, deposit ? points : -points, null, "api", System.currentTimeMillis(), plugin.maxBalance());
                } catch (java.io.IOException failure) {
                    String code = BankTransfers.code(failure);
                    return bankFailure(code.equals("insufficient") ? "vault.bank-insufficient"
                            : code.equals("full") ? "vault.bank-full" : "vault.bank-storage", balance);
                }
                return new EconomyResponse(points, ledger.get(name).map(BankLedger.Bank::balance).orElse(0L),
                        EconomyResponse.ResponseType.SUCCESS, "");
            }
        });
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        OfflinePlayer player = plugin.resolveKnownPlayer(playerName);
        return player == null ? unknownPlayer() : isBankOwner(name, player);
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return bankResult(name, ledger -> ledger.get(name).filter(b -> b.owner().equals(player.getUniqueId())).isPresent()
                ? new EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.SUCCESS, "")
                : bankFailure("vault.bank-not-owner", 0));
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        OfflinePlayer player = plugin.resolveKnownPlayer(playerName);
        return player == null ? unknownPlayer() : isBankMember(name, player);
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return bankResult(name, ledger -> ledger.get(name).filter(b -> b.member(player.getUniqueId())).isPresent()
                ? new EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.SUCCESS, "")
                : bankFailure("vault.bank-not-member", 0));
    }

    @Override
    public List<String> getBanks() {
        BankLedger ledger = plugin.vaultBanks();
        return ledger == null ? Collections.emptyList() : ledger.all().stream().map(BankLedger.Bank::name).toList();
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        OfflinePlayer player = plugin.resolveKnownPlayer(playerName);
        return player != null && createPlayerAccount(player);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return plugin.createAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }
}
