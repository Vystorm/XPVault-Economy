package local.xpeconomy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalEconomyPrefsTest {
    @Test void favoritePayeesRoundTripByUuidAndCurrentName() {
        UUID alex = UUID.randomUUID();
        UUID sam = UUID.randomUUID();
        String stored = PersonalEconomyPrefs.encodeFavorites(List.of(
                new PersonalEconomyPrefs.Payee(alex, "Alex"), new PersonalEconomyPrefs.Payee(sam, "Sam")));

        assertEquals(List.of(new PersonalEconomyPrefs.Payee(alex, "Alex"),
                new PersonalEconomyPrefs.Payee(sam, "Sam")), PersonalEconomyPrefs.parseFavorites(stored));
    }

    @Test void malformedDuplicatesAndExcessFavoritesAreIgnored() {
        UUID shared = UUID.randomUUID();
        List<PersonalEconomyPrefs.Payee> input = new ArrayList<>();
        input.add(new PersonalEconomyPrefs.Payee(shared, "First"));
        input.add(new PersonalEconomyPrefs.Payee(shared, "Duplicate"));
        for (int i = 0; i < 25; i++) input.add(new PersonalEconomyPrefs.Payee(UUID.randomUUID(), "P" + i));
        String stored = "not-a-row\n" + PersonalEconomyPrefs.encodeFavorites(input);

        var parsed = PersonalEconomyPrefs.parseFavorites(stored);
        assertEquals(PersonalEconomyPrefs.MAX_FAVORITES, parsed.size());
        assertEquals("First", parsed.getFirst().name());
        assertTrue(parsed.stream().noneMatch(payee -> payee.name().equals("Duplicate")));
    }

    @Test void labelsDropControlsAndStayWithinBound() {
        String label = PersonalEconomyPrefs.cleanLabel("  Gear\n\u0007 set  ");
        assertEquals("Gear set", label);
        assertEquals(PersonalEconomyPrefs.MAX_GOAL_LABEL,
                PersonalEconomyPrefs.cleanLabel("x".repeat(100)).length());
    }
}
