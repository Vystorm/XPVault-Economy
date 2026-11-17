package local.xpeconomy;

import java.util.UUID;

record StoredAccount(UUID uuid, String name, long balance, boolean initialized) {
}
