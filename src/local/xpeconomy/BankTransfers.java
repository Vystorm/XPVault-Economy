package local.xpeconomy;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The XP moves between players and {@link BankLedger} banks. The player side is booked in memory first; the bank write
 * then commits the booking and, in the same atomic write, journals the player's new balance ({@link PendingBookings}),
 * because player balances are only saved a few seconds later. When the bank write fails, the player booking is
 * reverted in memory. Callers hold the economy's transaction lock. Results are error codes ({@code null} = done) that
 * map to {@code bank.error.<code>}.
 */
final class BankTransfers {
    /** Player balance bookings (the economy's withdraw/deposit, journaled as "bank"). */
    interface Wallet {
        TransactionResult take(UUID player, long amount);
        TransactionResult give(UUID player, long amount);
        /** Journal entry for a booking that moved the player's balance from {@code before} to {@code after}; null = none. */
        default PendingBookings.Intent intent(UUID player, long before, long after) { return null; }
    }

    private BankTransfers() {}

    private static List<PendingBookings.Intent> journal(Wallet wallet, UUID player, long before, long after) {
        PendingBookings.Intent intent = wallet.intent(player, before, after);
        return intent == null ? List.of() : List.of(intent);
    }

    /** Player → bank: the XP leaves the player first; if the bank cannot be written it goes straight back. */
    static String deposit(BankLedger ledger, Wallet wallet, UUID player, String bank, long amount, long now, long maximum,
                          Consumer<String> severe) {
        if (amount <= 0) return "amount";
        var current = ledger.get(bank);
        if (!ledger.readable()) return "storage";
        if (current.isEmpty()) return "unknown";
        if (!current.get().member(player)) return "not-member";
        if (!current.get().owner().equals(player) && !current.get().allowance(player).deposit()) return "deposit-denied";
        if (current.get().balance() + amount > Math.min(Integer.MAX_VALUE, maximum)) return "full";
        TransactionResult taken = wallet.take(player, amount);
        if (!taken.success()) return "insufficient";
        try {
            ledger.change(bank, amount, player, "deposit", now, maximum, journal(wallet, player, taken.balance() + amount, taken.balance()));
            return null;
        } catch (IOException failure) {
            TransactionResult refund = wallet.give(player, amount);
            if (!refund.success())
                severe.accept("Bank deposit of " + amount + " XP by " + player + " into " + bank
                        + " failed and the refund failed too; manual balance review required: " + failure.getMessage());
            return code(failure);
        }
    }

    /** Bank → player: the player is credited in memory, then the bank write commits; if it fails the credit is taken back. */
    static String withdraw(BankLedger ledger, Wallet wallet, UUID player, String bank, long amount, long now, long maximum,
                           boolean admin, Consumer<String> severe) {
        if (amount <= 0) return "amount";
        var current = ledger.get(bank);
        if (!ledger.readable()) return "storage";
        if (current.isEmpty()) return "unknown";
        if (!admin && !current.get().member(player)) return "not-member";
        String policy = current.get().maySpend(player, amount, now);
        if (!admin && policy != null) return policy;
        if (current.get().balance() < amount) return "insufficient";
        TransactionResult given = wallet.give(player, amount);
        if (!given.success()) return "player-full";
        try {
            ledger.change(bank, -amount, player, "withdraw", now, maximum, journal(wallet, player, given.balance() - amount, given.balance()));
            return null;
        } catch (IOException failure) {
            TransactionResult back = wallet.take(player, amount);
            if (!back.success())
                severe.accept("Bank withdrawal of " + amount + " XP from " + bank + " by " + player
                        + " could not be saved and the XP could not be taken back; manual review required: " + failure.getMessage());
            return code(failure);
        }
    }

    /** Deletes a bank and pays its balance to the owner; if that does not fit, the bank stays. */
    static String close(BankLedger ledger, Wallet wallet, String bank, Consumer<String> severe) {
        var current = ledger.get(bank);
        if (!ledger.readable()) return "storage";
        if (current.isEmpty()) return "unknown";
        BankLedger.Bank closing = current.get();
        long balance = closing.balance();
        List<PendingBookings.Intent> intents = List.of();
        if (balance > 0) {
            TransactionResult paid = wallet.give(closing.owner(), balance);
            if (!paid.success()) return "owner-full";
            intents = journal(wallet, closing.owner(), paid.balance() - balance, paid.balance());
        }
        try {
            ledger.remove(bank, intents);
            return null;
        } catch (IOException failure) {
            if (balance > 0 && !wallet.take(closing.owner(), balance).success())
                severe.accept("Bank " + bank + " could not be removed and its " + balance + " XP paid to "
                        + closing.owner() + " could not be taken back; manual review required: " + failure.getMessage());
            return code(failure);
        }
    }

    /** Error code of a ledger exception; unknown messages are storage failures. */
    static String code(IOException failure) {
        String code = failure.getMessage();
        return switch (code == null ? "" : code) {
            case "name", "taken", "capacity", "unknown", "member", "members", "insufficient", "full", "invalid", "not-owner", "not-member" -> code;
            default -> "storage";
        };
    }
}
