
package org.example.subsidy.ai;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public final class IsolatedDocumentContentReader
        implements DocumentReader {

    private static final int MAX_INPUT_BYTES =
            10 * 1024 * 1024;

    private static final int MAX_TEXT_BYTES = 256 * 1024;
    private static final int MAX_IMAGES = 2;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private static final long TIMEOUT_SECONDS = 30;

    private static final Set<String> FORMATS = Set.of(
            "pdf", "docx", "txt", "png", "jpg", "jpeg"
    );

    private static final Set<String> ENVIRONMENT = Set.of(
            "PATH", "PATHEXT", "SYSTEMROOT", "WINDIR",
            "TEMP", "TMP", "TMPDIR", "HOME",
            "LANG", "LC_ALL", "TZ"
    );

    @Override
    public DocumentContent read(
            byte[] data,
            String filename
    ) throws IOException {

        if (data == null || data.length == 0) {
            throw new IOException("Empty document");
        }

        if (data.length > MAX_INPUT_BYTES) {
            throw new IOException("File exceeds size limit");
        }

        if (filename == null || !filename.contains(".")) {
            throw new IOException("Unknown file format");
        }

        String extension = filename.substring(
                filename.lastIndexOf('.') + 1
        ).toLowerCase(Locale.ROOT);

        if (!FORMATS.contains(extension)) {
            throw new IOException("Unsupported file format");
        }

        Path directory = Files.createTempDirectory(
                "subsidy-parser-"
        );

        Process process = null;

        try {
            Path input = directory.resolve(
                    "input." + extension
            );

            Path output = directory.resolve("output.bin");

            Files.write(input, data);

            ProcessBuilder builder = new ProcessBuilder(
                    buildCommand(
                            directory,
                            input,
                            output,
                            extension
                    )
            );

            builder.redirectOutput(
                    ProcessBuilder.Redirect.DISCARD
            );

            builder.redirectError(
                    ProcessBuilder.Redirect.DISCARD
            );

            builder.environment().keySet().removeIf(
                    key -> !ENVIRONMENT.contains(
                            key.toUpperCase(Locale.ROOT)
                    )
            );

            try {
                process = builder.start();
            } catch (IOException e) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Document parser is unavailable",
                        e
                );
            }

            boolean finished = process.waitFor(
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!finished) {
                process.destroyForcibly();

                throw new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "Document parsing timed out"
                );
            }

            if (process.exitValue() == 3) {
                throw new IOException(
                        "Document cannot be processed"
                );
            }

            if (process.exitValue() != 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Document parser failed"
                );
            }

            return readResult(output);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Document parsing interrupted",
                    e
            );

        } finally {

            if (process != null && process.isAlive()) {
                process.destroyForcibly();

                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            cleanup(directory);
        }
    }

    private static List<String> buildCommand(
            Path directory,
            Path input,
            Path output,
            String extension
    ) {

        boolean windows = System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("win");

        String java = Path.of(
                System.getProperty("java.home"),
                "bin",
                windows ? "java.exe" : "java"
        ).toString();

        List<String> command = new ArrayList<>(
                List.of(
                        java,
                        "-Xmx384m",
                        "-Djava.awt.headless=true",
                        "-Djava.io.tmpdir=" + directory
                )
        );

        String classPath =
                System.getProperty("java.class.path");

        if (!classPath.contains(File.pathSeparator)
                && classPath.toLowerCase(Locale.ROOT)
                .endsWith(".jar")
                && Files.isRegularFile(Path.of(classPath))) {

            command.add("-jar");
            command.add(classPath);

        } else {

            command.add("-cp");
            command.add(classPath);
            command.add("org.example.Main");
        }

        command.add("--ai-parser-worker");
        command.add(input.toString());
        command.add(output.toString());
        command.add(extension);

        return command;
    }

    private static DocumentContent  readResult(Path file)
            throws IOException {

        if (!Files.isRegularFile(file)
                || Files.size(file) >
                2L * MAX_IMAGE_BYTES + MAX_TEXT_BYTES + 8192) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Invalid document parser output"
            );
        }

        try (DataInputStream input =
                     new DataInputStream(
                             Files.newInputStream(file)
                     )) {

            if (input.readInt() != DocumentParserWorker.MAGIC) {
                throw new IOException("Invalid parser protocol");
            }

            String text = new String(
                    readBytes(input, MAX_TEXT_BYTES),
                    StandardCharsets.UTF_8
            );

            int count = input.readInt();

            if (count < 0 || count > MAX_IMAGES) {
                throw new IOException("Invalid image count");
            }

            List<byte[]> images = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                images.add(
                        readBytes(input, MAX_IMAGE_BYTES)
                );
            }

            if (input.read() != -1) {
                throw new IOException("Unexpected parser data");
            }

            return new DocumentContent (
                    text,
                    List.copyOf(images)
            );

        } catch (IOException e) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Invalid document parser response",
                    e
            );
        }
    }

    private static byte[] readBytes(
            DataInputStream input,
            int limit
    ) throws IOException {

        int length = input.readInt();

        if (length < 0 || length > limit) {
            throw new IOException("Invalid field length");
        }

        byte[] data = input.readNBytes(length);

        if (data.length != length) {
            throw new IOException("Incomplete field data");
        }

        return data;
    }

    private static void cleanup(Path directory) {

        try (Stream<Path> paths = Files.walk(directory)) {

            for (Path path : paths.sorted(
                    Comparator.reverseOrder()
            ).toList()) {

                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }

        } catch (IOException ignored) {
        }
    }
}
