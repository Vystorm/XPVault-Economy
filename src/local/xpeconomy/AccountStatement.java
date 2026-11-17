package local.xpeconomy;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** A snapshot of retained journal entries, not a reconstruction of the player's XP balance. */
record AccountStatement(UUID account, int days, long from, long until, List<TransactionHistory.Booking> rows) {
    record Totals(int count, long incoming, long outgoing) {
        long net() { return incoming - outgoing; }
    }
    AccountStatement { rows = List.copyOf(rows); }

    static Integer parseDays(String value) {
        return switch (value) { case "1" -> 1; case "7" -> 7; case "30" -> 30; default -> null; };
    }

    static AccountStatement create(UUID account, int days, long now, List<TransactionHistory.Booking> retained) {
        if (!List.of(1, 7, 30).contains(days)) throw new IllegalArgumentException("days");
        long from = now - days * 86_400_000L;
        var rows = retained.stream().filter(b -> b.account().equals(account) && b.time() >= from && b.time() <= now)
                .sorted(Comparator.comparingLong(TransactionHistory.Booking::time)).toList();
        return new AccountStatement(account, days, from, now, rows);
    }

    Totals total() { return sum(rows); }

    Map<String, Totals> byCause() {
        Map<String, Totals> result = new LinkedHashMap<>();
        for (String cause : List.of("pay", "standing", "bank", "credit", "debit", "set")) {
            var group = rows.stream().filter(b -> b.cause().equals(cause)).toList();
            if (!group.isEmpty()) result.put(cause, sum(group));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Totals sum(List<TransactionHistory.Booking> rows) {
        long incoming = 0, outgoing = 0;
        for (var b : rows) {
            long delta = b.after() - b.before();
            if (delta > 0) incoming += delta; else outgoing -= delta;
        }
        return new Totals(rows.size(), incoming, outgoing);
    }

    Path export(AtomicFilesInterface files, Path folder) throws IOException {
        StringBuilder csv = new StringBuilder("scope,account,from_utc,until_utc,count,incoming,outgoing,net\n");
        Totals total = total();
        csv.append("retained_journal_only,").append(account).append(',').append(Instant.ofEpochMilli(from)).append(',')
                .append(Instant.ofEpochMilli(until)).append(',').append(total.count).append(',').append(total.incoming)
                .append(',').append(total.outgoing).append(',').append(total.net()).append('\n');
        csv.append("\ncause,count,incoming,outgoing,net\n");
        byCause().forEach((cause, t) -> csv.append(cause).append(',').append(t.count).append(',').append(t.incoming)
                .append(',').append(t.outgoing).append(',').append(t.net()).append('\n'));
        csv.append("\npayment_id,time_utc,account,counterparty,cause,before,after,delta\n");
        for (var b : rows) csv.append(b.paymentId()).append(',').append(Instant.ofEpochMilli(b.time())).append(',')
                .append(account).append(',').append(b.counterparty() == null ? "" : b.counterparty()).append(',').append(b.cause())
                .append(',').append(b.before()).append(',').append(b.after()).append(',').append(b.after() - b.before()).append('\n');
        Path file = folder.resolve(account + "-statement-" + days + "d.csv");
        files.write(file, csv.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
