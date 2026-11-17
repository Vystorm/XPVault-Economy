package local.xpeconomy;

/** Preview-only arithmetic. Payments continue using the existing transfer path. */
record XpPaymentPreview(long before, long after, long currentLevel, long currentProgress, long currentNeed,
                        long projectedLevel, long projectedProgress, long projectedNeed) {
    static XpPaymentPreview of(long balance, long amount) {
        long[] before = BalanceRanking.levelProgress(balance);
        long[] after = BalanceRanking.levelProgress(Math.max(0L, balance - amount));
        return new XpPaymentPreview(balance, Math.max(0L, balance - amount), before[0], before[1], before[2],
                after[0], after[1], after[2]);
    }
}
