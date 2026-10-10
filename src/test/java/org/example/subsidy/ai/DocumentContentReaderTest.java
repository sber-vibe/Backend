
package org.example.subsidy.ai;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DocumentContentReaderTest {

    private final DocumentContentReader reader =
            new DocumentContentReader();


    @Test
    void rejectsDocxZipBomb() throws Exception {

        byte[] data;

        try (ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {

            try (ZipOutputStream zip =
                         new ZipOutputStream(output)) {

                zip.putNextEntry(
                        new ZipEntry("word/document.xml")
                );

                byte[] block = new byte[8192];

                for (int i = 0; i < 2049; i++) {
                    zip.write(block);
                }

                zip.closeEntry();
            }

            data = output.toByteArray();
        }

        IOException error = assertThrows(
                IOException.class,
                () -> reader.read(data, "bomb.docx")
        );

        assertTrue(
                error.getMessage().contains("safety limit")
        );
    }

    @Test
    void rejectsDocxWithTooManyEntries() throws Exception {

        byte[] data;

        try (ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {

            try (ZipOutputStream zip =
                         new ZipOutputStream(output)) {

                for (int i = 0; i < 257; i++) {
                    zip.putNextEntry(
                            new ZipEntry("entry-" + i + ".xml")
                    );
                    zip.write(1);
                    zip.closeEntry();
                }
            }

            data = output.toByteArray();
        }

        IOException error = assertThrows(
                IOException.class,
                () -> reader.read(data, "many-entries.docx")
        );

        assertTrue(
                error.getMessage().contains("entry count")
        );
    }

    @Test
    void rejectsZipDisguisedAsDocx() throws Exception {

        byte[] data;

        try (ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {

            try (ZipOutputStream zip =
                         new ZipOutputStream(output)) {

                zip.putNextEntry(
                        new ZipEntry("unrelated.txt")
                );

                zip.write(
                        "Not a Word document"
                                .getBytes(StandardCharsets.UTF_8)
                );

                zip.closeEntry();
            }

            data = output.toByteArray();
        }

        IOException error = assertThrows(
                IOException.class,
                () -> reader.read(data, "fake.docx")
        );

        assertEquals(
                "Invalid DOCX structure",
                error.getMessage()
        );
    }


    @Test
    void readsUtf8TextFile() throws Exception {

        byte[] data = """
                Заявление на предоставление субсидии
                ООО Ромашка
                ИНН 7707083893
                """.getBytes(StandardCharsets.UTF_8);

        var content = reader.read(data, "document.txt");

        assertTrue(content.text().contains("ООО Ромашка"));
        assertTrue(content.text().contains("7707083893"));
        assertTrue(content.images().isEmpty());
    }

    @Test
    void readsWordDocumentWithTable() throws Exception {

        byte[] data;

        try (XWPFDocument word = new XWPFDocument();
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {

            word.createParagraph().createRun()
                    .setText("Заявление на субсидию");

            var table = word.createTable(1, 2);

            table.getRow(0).getCell(0)
                    .setText("Организация");

            table.getRow(0).getCell(1)
                    .setText("ООО Ромашка");

            word.write(output);
            data = output.toByteArray();
        }

        var content = reader.read(data, "document.docx");

        assertTrue(content.text().contains("Заявление"));
        assertTrue(content.text().contains("ООО Ромашка"));
        assertTrue(content.images().isEmpty());
    }

    @Test
    void readsPdfWithTextLayer() throws Exception {

        byte[] data;

        try (PDDocument pdf = new PDDocument();
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {

            PDPage page = new PDPage();
            pdf.addPage(page);

            try (PDPageContentStream stream =
                         new PDPageContentStream(pdf, page)) {

                stream.beginText();
                stream.setFont(
                        new PDType1Font(
                                Standard14Fonts.FontName.HELVETICA
                        ), 12
                );

                stream.newLineAtOffset(72, 700);
                stream.showText("APPLICATION DOCUMENT");
                stream.endText();
            }

            pdf.save(output);
            data = output.toByteArray();
        }

        var content = reader.read(data, "application.pdf");

        assertTrue(content.text().contains(
                "APPLICATION DOCUMENT"
        ));

        assertTrue(content.images().isEmpty());
    }

    @Test
    void handlesMixedPdf() throws Exception {

        byte[] data;

        try (PDDocument pdf = new PDDocument();
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {

            // Первая страница: обычный текст.
            PDPage textPage = new PDPage();
            pdf.addPage(textPage);

            try (PDPageContentStream stream =
                         new PDPageContentStream(pdf, textPage)) {

                stream.beginText();
                stream.setFont(
                        new PDType1Font(
                                Standard14Fonts.FontName.HELVETICA
                        ), 12
                );

                stream.newLineAtOffset(72, 700);
                stream.showText("APPLICATION");
                stream.endText();
            }

            // Вторая страница: только изображение.
            PDPage scannedPage = new PDPage();
            pdf.addPage(scannedPage);

            BufferedImage image = new BufferedImage(
                    300, 150, BufferedImage.TYPE_INT_RGB
            );

            try {
                var graphics = image.createGraphics();

                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, 300, 150);

                    graphics.setColor(Color.BLACK);
                    graphics.drawString("SCANNED PAGE", 20, 70);
                } finally {
                    graphics.dispose();
                }

                var pdfImage =
                        LosslessFactory.createFromImage(pdf, image);

                try (PDPageContentStream stream =
                             new PDPageContentStream(pdf, scannedPage)) {

                    stream.drawImage(
                            pdfImage, 72, 500, 300, 150
                    );
                }

            } finally {
                image.flush();
            }

            pdf.save(output);
            data = output.toByteArray();
        }

        var content = reader.read(data, "mixed.pdf");

        assertTrue(content.text().contains("APPLICATION"));

        // Вторая страница должна пойти в Qwen3-VL
        // как изображение, а не как текст.
        assertEquals(1, content.images().size());

        assertTrue(content.images().get(0).length > 0);
    }


    @Test
    void rejectsOversizedPngBeforeDecoding() throws Exception {

        // Создаём крошечный корректный PNG.
        BufferedImage image = new BufferedImage(
                1, 1, BufferedImage.TYPE_INT_RGB
        );

        byte[] data;

        try (ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {

            assertTrue(ImageIO.write(image, "png", output));
            data = output.toByteArray();

        } finally {
            image.flush();
        }

        // Подменяем объявленные размеры PNG в заголовке.
        // Сами пиксели НЕ создаём, поэтому тест безопасен.
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.putInt(16, 50000);
        buffer.putInt(20, 50000);

        // Обновляем контрольную сумму заголовка PNG.
        CRC32 crc = new CRC32();
        crc.update(data, 12, 17);
        buffer.putInt(29, (int) crc.getValue());

        IOException error = assertThrows(
                IOException.class,
                () -> reader.read(data, "oversized.png")
        );

        assertTrue(
                error.getMessage().contains("safety limit")
        );
    }

    @Test
    void rejectsHugePdfPageBeforeRendering() throws Exception {

        byte[] data;

        try (PDDocument pdf = new PDDocument();
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {
            pdf.addPage(
                    new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(
                            8000, 8000
                    ))
            );

            pdf.save(output);
            data = output.toByteArray();
        }

        IOException error = assertThrows(
                IOException.class,
                () -> reader.read(data, "huge-page.pdf")
        );

        assertTrue(
                error.getMessage().contains("safety limit")
        );
    }


    @Test
    void acceptsPngAndJpegImages() throws Exception {

        BufferedImage image = new BufferedImage(
                100, 100, BufferedImage.TYPE_INT_RGB
        );

        try {
            for (String format : new String[]{"png", "jpg"}) {

                byte[] data;

                try (ByteArrayOutputStream output =
                             new ByteArrayOutputStream()) {

                    assertTrue(ImageIO.write(image, format, output));
                    data = output.toByteArray();
                }

                var content = reader.read(
                        data, "document." + format
                );

                assertTrue(content.text().isEmpty());
                assertEquals(1, content.images().size());
                assertArrayEquals(data, content.images().get(0));
            }
        } finally {
            image.flush();
        }
    }

    @Test
    void rejectsInvalidAndOversizedContent() {

        assertThrows(IOException.class, () ->
                reader.read(
                        "not a PDF".getBytes(StandardCharsets.UTF_8),
                        "document.pdf"
                )
        );

        assertThrows(IOException.class, () ->
                reader.read(
                        "X".repeat(6001)
                                .getBytes(StandardCharsets.UTF_8),
                        "document.txt"
                )
        );

        assertThrows(IOException.class, () ->
                reader.read(
                        new byte[]{1, 2, 3, 4},
                        "document.jpg"
                )
        );
    }
}
