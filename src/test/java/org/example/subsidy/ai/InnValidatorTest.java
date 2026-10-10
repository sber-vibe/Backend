package org.example.subsidy.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InnValidatorTest {

    @Test
    void acceptsTenDigitOrganizationInn() {
        assertTrue(InnValidator.isValid("7707083893"));
    }

    @Test
    void rejectsChangedOcrDigit() {
        assertFalse(InnValidator.isValid("1707083893"));
    }

    @Test
    void doesNotTrustIncorrectDigitsInOriginal() {
        assertFalse(InnValidator.isValid("7725123456"));
    }

    @Test
    void acceptsTwelveDigitInn() {
        assertTrue(InnValidator.isValid("500100732259"));
    }

    @Test
    void rejectsMalformedValues() {
        assertFalse(InnValidator.isValid(null));
        assertFalse(InnValidator.isValid(""));
        assertFalse(InnValidator.isValid("12345"));
        assertFalse(InnValidator.isValid("770708389A"));
    }
}
