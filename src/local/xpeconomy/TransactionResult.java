package local.xpeconomy;

/** Result of a balance change; {@code error} is a language key (see lang/en.yml "error.*"), empty on success. */
record TransactionResult(boolean success, long amount, long balance, String error) {
    static TransactionResult success(long amount, long balance) {
        return new TransactionResult(true, amount, balance, "");
    }

    static TransactionResult failure(long balance, String error) {
        return new TransactionResult(false, 0L, balance, error);
    }
}
