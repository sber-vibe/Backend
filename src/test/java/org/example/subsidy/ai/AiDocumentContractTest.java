
package org.example.subsidy.ai;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class AiDocumentContractTest {

    @Test
    void supportsEightOfficialDocumentTypes() {
        assertEquals(
                9,
                AiDocumentContract.DOCUMENT_TYPES.size()
        );

        assertEquals(
                9,
                new HashSet<>(
                        AiDocumentContract.DOCUMENT_TYPES
                ).size()
        );
    }

    @Test
    void containsRequiredDocumentTypes() {
        assertAll(
                () -> assertTrue(AiDocumentContract.DOCUMENT_TYPES
                        .contains(AiDocumentContract.APPLICATION)),
                () -> assertTrue(AiDocumentContract.DOCUMENT_TYPES
                        .contains(AiDocumentContract.PERSONAL_DATA_CONSENT)),
                () -> assertTrue(AiDocumentContract.DOCUMENT_TYPES
                        .contains(AiDocumentContract.CONFORMITY_CERTIFICATE)),
                () -> assertTrue(AiDocumentContract.DOCUMENT_TYPES
                        .contains(AiDocumentContract.PROJECT_PRESENTATION)),
                () -> assertTrue(AiDocumentContract.DOCUMENT_TYPES
                        .contains(AiDocumentContract.COST_JUSTIFICATION)),
                () -> assertTrue(AiDocumentContract.DOCUMENT_TYPES
                        .contains(AiDocumentContract.BANK_STATEMENT)),
                () -> assertTrue(AiDocumentContract.DOCUMENT_TYPES
                        .contains(AiDocumentContract.REAL_ESTATE_DOCUMENT)),
                () -> assertTrue(AiDocumentContract.DOCUMENT_TYPES
                        .contains(AiDocumentContract.GUARANTEE_LETTER))
        );
    }

    @Test
    void keepsUnknownAsFallback() {
        assertTrue(
                AiDocumentContract.DOCUMENT_TYPES
                        .contains(AiDocumentContract.UNKNOWN)
        );
    }
}
