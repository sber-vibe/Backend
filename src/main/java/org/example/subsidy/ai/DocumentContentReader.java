
package org.example.subsidy.ai;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DocumentContentReader implements DocumentReader {

    private static final int MAX_PAGES = 30;
    private static final int MAX_IMAGE_PAGES = 2;
    private static final int MAX_TEXT_LENGTH = 64_000;
    private static final long MAX_PIXELS = 20_000_000L;
    private static final int MAX_FILE_BYTES = 20 * 1024 * 1024;

    private static final int MAX_DOCX_ENTRIES = 256;
    private static final long MAX_DOCX_ENTRY_BYTES = 16L * 1024 * 1024;
    private static final long MAX_DOCX_TOTAL_BYTES = 32L * 1024 * 1024;

    private static final long MAX_PDF_CACHE_MEMORY = 8L * 1024 * 1024;
    private static final long MAX_PDF_CACHE_TOTAL = 64L * 1024 * 1024;
    private static final int MAX_PDF_IMAGE_BYTES = 8 * 1024 * 1024;

    @Override
    public DocumentContent read(byte[] data, String filename)
            throws IOException {

        if (data == null || data.length == 0) {
            throw new IOException("Empty document");
        }

        if (filename == null || !filename.contains(".")) {
            throw new IOException("Unknown file format");
        }

        if (data.length > MAX_FILE_BYTES) {
            throw new IOException("File exceeds size limit");
        }

        String extension = filename.substring(
                filename.lastIndexOf('.') + 1
        ).toLowerCase(Locale.ROOT);

        DocumentContent content = switch (extension) {

            case "pdf" -> {
                require(startsWith(data,
                        new byte[]{'%', 'P', 'D', 'F', '-'}));
                yield readPdf(data);
            }

            case "docx" -> {
                require(startsWith(data,
                        new byte[]{'P', 'K', 3, 4}));
                validateDocxArchive(data);
                yield new DocumentContent(readDocx(data), List.of());
            }

            case "txt" ->
                    new DocumentContent(readTxt(data), List.of());

            case "png" -> {
                require(startsWith(data, new byte[]{
                        (byte) 137, 80, 78, 71, 13, 10, 26, 10
                }));
                validateImage(data);
                yield new DocumentContent("", List.of(data));
            }

            case "jpg", "jpeg" -> {
                require(startsWith(data, new byte[]{
                        (byte) 255, (byte) 216, (byte) 255
                }));
                validateImage(data);
                yield new DocumentContent("", List.of(data));
            }

            default -> throw new IOException(
                    "Unsupported format: " + extension
            );
        };

        if (content.text().length() > MAX_TEXT_LENGTH) {
            throw new IOException(
                    "Document text exceeds safety limit"
            );
        }

        if (content.text().isBlank()
                && content.images().isEmpty()) {
            throw new IOException("No readable document content");
        }

        return content;
    }

    private DocumentContent readPdf(byte[] data) throws IOException {

        try (PDDocument pdf = Loader.loadPDF(
                data,
                "",
                null,
                null,
                MemoryUsageSetting.setupMixed(
                        MAX_PDF_CACHE_MEMORY,
                        MAX_PDF_CACHE_TOTAL
                ).streamCache
        )) {

            int pageCount = pdf.getNumberOfPages();

            if (pageCount == 0 || pageCount > MAX_PAGES) {
                throw new IOException(
                        "Unsupported PDF page count: " + pageCount
                );
            }

            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(pdf);

            StringBuilder text = new StringBuilder();
            List<byte[]> images = new ArrayList<>();
            List<Integer> budgetPages = new ArrayList<>();

            for (int i = 0; i < pageCount; i++) {

                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);

                String pageText = stripper.getText(pdf).strip();
                String lower = pageText.toLowerCase(Locale.ROOT);

                if (lower.contains("запрашиваем")
                        && lower.contains("софинансирован")
                        && lower.contains("итого")) {
                    budgetPages.add(i);
                }

                if (!pageText.isBlank()) {

                    text.append("\n[Страница ")
                            .append(i + 1)
                            .append("]\n")
                            .append(pageText)
                            .append("\n");

                } else {

                    if (images.size() >= MAX_IMAGE_PAGES) {
                        throw new IOException(
                                "Too many scanned PDF pages"
                        );
                    }

                    images.add(
                            renderPage(renderer, pdf, i, 180)
                    );

                    text.append("\n[Страница ")
                            .append(i + 1)
                            .append(": изображение №")
                            .append(images.size())
                            .append("]\n");
                }

                if (text.length() > MAX_TEXT_LENGTH) {
                    throw new IOException(
                            "Document text exceeds safety limit"
                    );
                }
            }

            if (images.isEmpty() && !budgetPages.isEmpty()) {

                int start = Math.max(
                        0,
                        budgetPages.size() - MAX_IMAGE_PAGES
                );

                for (int j = start; j < budgetPages.size(); j++) {
                    images.add(
                            renderPage(
                                    renderer,
                                    pdf,
                                    budgetPages.get(j),
                                    140
                            )
                    );
                }
            }

            return new DocumentContent(
                    text.toString().strip(),
                    List.copyOf(images)
            );
        }
    }

    private byte[] renderPage(
            PDFRenderer renderer,
            PDDocument pdf,
            int pageIndex,
            int dpi
    ) throws IOException {

        PDPage page = pdf.getPage(pageIndex);
        PDRectangle cropBox = page.getCropBox();

        double scale = (dpi / 72.0) * page.getUserUnit();

        double widthPixels =
                Math.ceil(cropBox.getWidth() * scale);

        double heightPixels =
                Math.ceil(cropBox.getHeight() * scale);

        if (!Double.isFinite(widthPixels)
                || !Double.isFinite(heightPixels)
                || widthPixels <= 0
                || heightPixels <= 0
                || widthPixels > 8000
                || heightPixels > 8000
                || widthPixels * heightPixels > MAX_PIXELS) {

            throw new IOException(
                    "PDF page dimensions exceed safety limit"
            );
        }

        BufferedImage image =
                renderer.renderImageWithDPI(
                        pageIndex,
                        dpi,
                        ImageType.RGB
                );

        try {
            long actualPixels =
                    (long) image.getWidth() * image.getHeight();

            if (actualPixels > MAX_PIXELS) {
                throw new IOException(
                        "PDF page image exceeds safety limit"
                );
            }

            try (BoundedByteArrayOutputStream output =
                         new BoundedByteArrayOutputStream(
                                 MAX_PDF_IMAGE_BYTES
                         )) {

                if (!ImageIO.write(image, "png", output)) {
                    throw new IOException(
                            "Cannot encode PDF page"
                    );
                }

                return output.toByteArray();
            }

        } finally {
            image.flush();
        }
    }

    private String readDocx(byte[] data) throws IOException {

        try (ByteArrayInputStream input =
                     new ByteArrayInputStream(data);
             XWPFDocument document = new XWPFDocument(input);
             XWPFWordExtractor extractor =
                     new XWPFWordExtractor(document)) {

            return extractor.getText().strip();
        }
    }

    private String readTxt(byte[] data) throws IOException {

        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        String text = decoder.decode(
                ByteBuffer.wrap(data)
        ).toString();

        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }

        if (text.indexOf('\0') >= 0) {
            throw new IOException("Invalid TXT content");
        }

        return text.strip();
    }

    private void validateImage(byte[] data) throws IOException {

        try (ImageInputStream input =
                     new MemoryCacheImageInputStream(
                             new ByteArrayInputStream(data)
                     )) {

            Iterator<ImageReader> readers =
                    ImageIO.getImageReaders(input);

            if (!readers.hasNext()) {
                throw new IOException("Unsupported image format");
            }

            ImageReader reader = readers.next();

            try {
                reader.setInput(input, true, true);

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                long pixels = (long) width * height;

                if (width <= 0 || height <= 0
                        || pixels > MAX_PIXELS) {
                    throw new IOException(
                            "Image dimensions exceed safety limit"
                    );
                }

                BufferedImage image = reader.read(0);

                if (image == null) {
                    throw new IOException("Cannot decode image");
                }

                image.flush();

            } finally {
                reader.dispose();
            }
        }
    }

    private void validateDocxArchive(byte[] data)
            throws IOException {

        int entryCount = 0;
        long totalBytes = 0;

        boolean hasContentTypes = false;
        boolean hasDocument = false;

        byte[] buffer = new byte[8192];

        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(data))) {

            ZipEntry entry;

            while ((entry = zip.getNextEntry()) != null) {

                entryCount++;

                if (entryCount > MAX_DOCX_ENTRIES) {
                    throw new IOException(
                            "DOCX entry count exceeds safety limit"
                    );
                }

                if (entry.getName().equals("[Content_Types].xml")) {
                    hasContentTypes = true;
                }

                if (entry.getName().equals("word/document.xml")) {
                    hasDocument = true;
                }

                long entryBytes = 0;
                int bytesRead;

                while ((bytesRead = zip.read(buffer)) != -1) {

                    entryBytes += bytesRead;
                    totalBytes += bytesRead;

                    if (entryBytes > MAX_DOCX_ENTRY_BYTES) {
                        throw new IOException(
                                "DOCX entry size exceeds safety limit"
                        );
                    }

                    if (totalBytes > MAX_DOCX_TOTAL_BYTES) {
                        throw new IOException(
                                "DOCX expanded size exceeds safety limit"
                        );
                    }
                }

                zip.closeEntry();
            }
        }

        if (!hasContentTypes || !hasDocument) {
            throw new IOException("Invalid DOCX structure");
        }
    }

    private static boolean startsWith(
            byte[] data,
            byte[] prefix
    ) {
        if (data.length < prefix.length) {
            return false;
        }

        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }

        return true;
    }

    private static void require(boolean valid)
            throws IOException {

        if (!valid) {
            throw new IOException(
                    "File content does not match its format"
            );
        }
    }
}
