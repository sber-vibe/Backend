package org.example.subsidy;

import java.util.List;
import static org.example.subsidy.Models.*;

/** Shared contract between the asynchronous application queue and the AI provider. */
public interface DocumentRecognizer {
    Recognition recognize(byte[] content, String filename) throws Exception;

    record Recognition(DocumentType detectedType, Double confidence, List<ExtractedField> fields) {
    }
}
