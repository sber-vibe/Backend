package org.example.subsidy.ai;

import java.io.*;
import java.util.Locale;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.ss.usermodel.*;

/** Static text/table preview. No macros, formulas, links or embedded resources are executed. */
final class OfficePreview {
    private static final int MAX_OUTPUT = 1_000_000;
    private OfficePreview() { }

    static String render(byte[] content, String extension) throws IOException {
        String body;
        if ("doc".equals(extension) || "docx".equals(extension)) body = "<pre>" + escape(word(content, extension)) + "</pre>";
        else body = spreadsheet(content, true);
        if (body.length() > MAX_OUTPUT) throw new IOException("Preview size limit exceeded");
        return "<!doctype html><html lang=\"ru\"><head><meta charset=\"UTF-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\"><style>body{font:16px system-ui;margin:24px;color:#172b24}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}table{border-collapse:collapse;max-width:100%}td{border:1px solid #ccc;padding:6px;white-space:pre-wrap;overflow-wrap:anywhere}h2{font-size:18px}</style></head><body>" + body + "</body></html>";
    }

    static String text(byte[] content, String extension) throws IOException {
        String text = "doc".equals(extension) ? word(content, extension) : spreadsheet(content, false);
        return text.length() <= 6000 ? text : text.substring(0, 6000);
    }
    private static String word(byte[] content, String extension) throws IOException {
        if ("docx".equals(extension)) {
            DocumentContentReader.validateDocxArchive(content);
            try (var document = new XWPFDocument(new ByteArrayInputStream(content)); var extractor = new XWPFWordExtractor(document)) {
                String text = extractor.getText();
                if (text.length() > MAX_OUTPUT) throw new IOException("Preview size limit exceeded");
                return text;
            }
        }
        try (var extractor = new WordExtractor(new ByteArrayInputStream(content))) {
            String text = extractor.getText();
            if (text.length() > MAX_OUTPUT) throw new IOException("Preview size limit exceeded");
            return text;
        }
    }
    private static String spreadsheet(byte[] content, boolean html) throws IOException {
        if (content.length >= 4 && content[0] == 'P' && content[1] == 'K') DocumentContentReader.validateOfficeArchive(content, "xl/workbook.xml");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() > 20) throw new IOException("Too many worksheets");
            var formatter = new DataFormatter(Locale.forLanguageTag("ru"));
            formatter.setUseCachedValuesForFormulaCells(true);
            var output = new StringBuilder();
            int cells = 0;
            for (var sheet : workbook) {
                if (sheet.getLastRowNum() > 2000) throw new IOException("Too many rows");
                output.append(html ? "<h2>" + escape(sheet.getSheetName()) + "</h2><table>" : sheet.getSheetName() + "\n");
                for (var row : sheet) {
                    int columns = Math.max(0, row.getLastCellNum());
                    if (columns > 100 || (cells += columns) > 10_000) throw new IOException("Too many cells");
                    if (html) output.append("<tr>");
                    for (int index = 0; index < columns; index++) {
                        String value = formatter.formatCellValue(row.getCell(index));
                        output.append(html ? "<td>" + escape(value) + "</td>" : value + "\t");
                        if (output.length() > MAX_OUTPUT) throw new IOException("Preview size limit exceeded");
                    }
                    output.append(html ? "</tr>" : "\n");
                }
                if (html) output.append("</table>");
            }
            return output.toString();
        } catch (RuntimeException exception) { throw new IOException("Unable to read spreadsheet", exception); }
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
