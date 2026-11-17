package local.xpeconomy;

import Vystorm.vystorm_core._uiMethods.settings.PlayerPrefsInterface;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Personal, non-financial preferences stored with the online player's persistent data. Main-thread only. */
final class PersonalEconomyPrefs {
    static final String GOAL_TARGET = "saving-goal-target";
    static final String GOAL_LABEL = "saving-goal-label";
    static final String GOAL_NOTIFY = "saving-goal-notify";
    static final String GOAL_NOTIFIED_TARGET = "saving-goal-notified-target";
    static final String FAVORITES = "favorite-payees";
    static final int MAX_FAVORITES = 20;
    static final int MAX_GOAL_LABEL = 48;

    record Goal(long target, String label, boolean notifyOnReach) { }
    record Payee(UUID uuid, String name) { }

    private PersonalEconomyPrefs() { }

    static Goal goal(PlayerPrefsInterface prefs, Plugin plugin, Player player) {
        double rawTarget = prefs.number(plugin, player, GOAL_TARGET, 0.0D);
        if (!Double.isFinite(rawTarget) || rawTarget < 1.0D || rawTarget > Long.MAX_VALUE) return null;
        long target = (long) rawTarget;
        if (rawTarget != target) return null;
        String label = prefs.text(plugin, player, GOAL_LABEL, "").strip();
        return new Goal(target, label, prefs.bool(plugin, player, GOAL_NOTIFY, false));
    }

    static void setGoal(PlayerPrefsInterface prefs, Plugin plugin, Player player, long target, String label) {
        prefs.setNumber(plugin, player, GOAL_TARGET, target);
        prefs.setText(plugin, player, GOAL_LABEL, cleanLabel(label));
        prefs.setNumber(plugin, player, GOAL_NOTIFIED_TARGET, 0.0D);
    }

    static void clearGoal(PlayerPrefsInterface prefs, Plugin plugin, Player player) {
        prefs.clear(plugin, player, GOAL_TARGET);
        prefs.clear(plugin, player, GOAL_LABEL);
        prefs.clear(plugin, player, GOAL_NOTIFY);
        prefs.clear(plugin, player, GOAL_NOTIFIED_TARGET);
    }

    static String cleanLabel(String raw) {
        if (raw == null) return "";
        StringBuilder clean = new StringBuilder(Math.min(raw.length(), MAX_GOAL_LABEL));
        for (int i = 0; i < raw.length() && clean.length() < MAX_GOAL_LABEL; i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) clean.append(c);
        }
        return clean.toString().strip();
    }

    static List<Payee> favorites(PlayerPrefsInterface prefs, Plugin plugin, Player player) {
        return parseFavorites(prefs.text(plugin, player, FAVORITES, ""));
    }

    static boolean addFavorite(PlayerPrefsInterface prefs, Plugin plugin, Player player, UUID uuid, String name) {
        if (uuid == null) return false;
        List<Payee> payees = new ArrayList<>(favorites(prefs, plugin, player));
        String currentName = cleanPlayerName(name);
        for (int i = 0; i < payees.size(); i++) {
            if (!payees.get(i).uuid().equals(uuid)) continue;
            payees.set(i, new Payee(uuid, currentName));
            prefs.setText(plugin, player, FAVORITES, encodeFavorites(payees));
            return true;
        }
        if (payees.size() >= MAX_FAVORITES) return false;
        payees.add(new Payee(uuid, currentName));
        prefs.setText(plugin, player, FAVORITES, encodeFavorites(payees));
        return true;
    }

    static boolean removeFavorite(PlayerPrefsInterface prefs, Plugin plugin, Player player, UUID uuid) {
        List<Payee> payees = new ArrayList<>(favorites(prefs, plugin, player));
        boolean removed = payees.removeIf(payee -> payee.uuid().equals(uuid));
        if (removed) prefs.setText(plugin, player, FAVORITES, encodeFavorites(payees));
        return removed;
    }

    static void refreshFavoriteName(PlayerPrefsInterface prefs, Plugin plugin, Player owner, UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) return;
        List<Payee> payees = new ArrayList<>(favorites(prefs, plugin, owner));
        for (int i = 0; i < payees.size(); i++) {
            Payee payee = payees.get(i);
            if (payee.uuid().equals(uuid) && !payee.name().equals(name)) {
                payees.set(i, new Payee(uuid, cleanPlayerName(name)));
                prefs.setText(plugin, owner, FAVORITES, encodeFavorites(payees));
                return;
            }
        }
    }

    static String cleanPlayerName(String name) {
        if (name == null) return "";
        String clean = name.strip();
        if (clean.length() > 16) clean = clean.substring(0, 16);
        return clean;
    }

    static List<Payee> parseFavorites(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        List<Payee> result = new ArrayList<>();
        for (String line : encoded.split("\\n")) {
            if (result.size() >= MAX_FAVORITES) break;
            int separator = line.indexOf('|');
            if (separator <= 0) continue;
            try {
                UUID uuid = UUID.fromString(line.substring(0, separator));
                String name = new String(Base64.getUrlDecoder().decode(line.substring(separator + 1)), StandardCharsets.UTF_8);
                if (name.isBlank() || name.length() > 16 || name.chars().anyMatch(Character::isISOControl)
                        || result.stream().anyMatch(payee -> payee.uuid().equals(uuid))) continue;
                result.add(new Payee(uuid, name));
            } catch (IllegalArgumentException ignored) {
                // Skip a malformed preference row; never let one bad entry hide the rest.
            }
        }
        return List.copyOf(result);
    }

    static String encodeFavorites(List<Payee> payees) {
        StringBuilder out = new StringBuilder();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        int count = 0;
        for (Payee payee : payees) {
            if (payee == null || payee.uuid() == null || count >= MAX_FAVORITES || !seen.add(payee.uuid())) continue;
            String name = cleanPlayerName(payee.name());
            if (name.isBlank()) continue;
            if (!out.isEmpty()) out.append('\n');
            out.append(payee.uuid()).append('|').append(Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(name.getBytes(StandardCharsets.UTF_8)));
            count++;
        }
        return out.toString();
    }
}
