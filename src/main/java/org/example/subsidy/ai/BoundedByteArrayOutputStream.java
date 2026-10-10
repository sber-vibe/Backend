
package org.example.subsidy.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

final class BoundedByteArrayOutputStream extends OutputStream {

    private final ByteArrayOutputStream buffer =
            new ByteArrayOutputStream();

    private final int maxBytes;

    BoundedByteArrayOutputStream(int maxBytes) {

        if (maxBytes <= 0) {
            throw new IllegalArgumentException(
                    "Maximum size must be positive"
            );
        }

        this.maxBytes = maxBytes;
    }

    @Override
    public void write(int value) throws IOException {

        ensureCapacity(1);
        buffer.write(value);
    }

    @Override
    public void write(
            byte[] data,
            int offset,
            int length
    ) throws IOException {

        Objects.checkFromIndexSize(
                offset, length, data.length
        );

        ensureCapacity(length);
        buffer.write(data, offset, length);
    }

    private void ensureCapacity(int count)
            throws IOException {

        if (count > maxBytes - buffer.size()) {
            throw new IOException(
                    "PDF image exceeds output size limit"
            );
        }
    }

    byte[] toByteArray() {
        return buffer.toByteArray();
    }
}
