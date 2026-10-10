
package org.example.subsidy.ai;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DocumentParserWorker {

    static final int MAGIC = 0x53424331;

    private DocumentParserWorker() {
    }

    public static int run(
            String inputPath,
            String outputPath,
            String extension
    ) {
        return run(inputPath, outputPath, extension, false);
    }

    public static int run(String inputPath, String outputPath, String extension, boolean preview) {
        try {
            byte[] data = Files.readAllBytes(
                    Path.of(inputPath)
            );

            DocumentContent content = preview ? new DocumentContent(OfficePreview.render(data, extension), java.util.List.of()) :
                    new DocumentContentReader().read(
                            data,
                            "document." + extension
                    );

            try (DataOutputStream output =
                         new DataOutputStream(
                                 Files.newOutputStream(
                                         Path.of(outputPath)
                                 )
                         )) {

                output.writeInt(MAGIC);

                writeBytes(
                        output,
                        content.text().getBytes(
                                StandardCharsets.UTF_8
                        )
                );

                output.writeInt(content.images().size());

                for (byte[] image : content.images()) {
                    writeBytes(output, image);
                }
            }

            return 0;

        } catch (IOException | RuntimeException e) {
            return 3;
        }
    }

    private static void writeBytes(
            DataOutputStream output,
            byte[] data
    ) throws IOException {

        output.writeInt(data.length);
        output.write(data);
    }
}
