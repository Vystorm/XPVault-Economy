package local.xpeconomy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XpPaymentPreviewTest {
    @Test void paymentPreviewUsesTheVanillaNonlinearLevelCurve() {
        XpPaymentPreview preview = XpPaymentPreview.of(20L, 9L);
        assertEquals(20L, preview.before());
        assertEquals(11L, preview.after());
        assertEquals(2L, preview.currentLevel());
        assertEquals(4L, preview.currentProgress());
        assertEquals(11L, preview.currentNeed());
        assertEquals(1L, preview.projectedLevel());
        assertEquals(4L, preview.projectedProgress());
        assertEquals(9L, preview.projectedNeed());
    }

    @Test void projectedBalanceNeverGoesBelowZero() {
        XpPaymentPreview preview = XpPaymentPreview.of(5L, 9L);
        assertEquals(0L, preview.after());
        assertEquals(0L, preview.projectedLevel());
        assertEquals(0L, preview.projectedProgress());
    }
}
