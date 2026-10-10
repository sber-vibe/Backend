
package org.example.subsidy.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserWorkerTest {

    @TempDir
    Path directory;

    @Test
    void writesValidDocumentResult() throws Exception {

        Path input = directory.resolve("input.txt");
        Path output = directory.resolve("output.bin");

        Files.writeString(
                input,
                "Заявление ООО Ромашка",
                StandardCharsets.UTF_8
        );

        int exitCode = DocumentParserWorker.run(
                input.toString(),
                output.toString(),
                "txt"
        );

        assertEquals(0, exitCode);
        assertTrue(Files.isRegularFile(output));

        try (DataInputStream data =
                     new DataInputStream(
                             Files.newInputStream(output)
                     )) {

            assertEquals(
                    DocumentParserWorker.MAGIC,
                    data.readInt()
            );

            int length = data.readInt();

            String text = new String(
                    data.readNBytes(length),
                    StandardCharsets.UTF_8
            );

            assertEquals("Заявление ООО Ромашка", text);
            assertEquals(0, data.readInt());
            assertEquals(-1, data.read());
        }
    }

    @Test
    void rejectsDamagedDocument() throws Exception {

        Path input = directory.resolve("invalid.pdf");
        Path output = directory.resolve("output.bin");

        Files.writeString(input, "Invalid PDF");

        int exitCode = DocumentParserWorker.run(
                input.toString(),
                output.toString(),
                "pdf"
        );

        assertEquals(3, exitCode);
        assertFalse(Files.exists(output));
    }
}
