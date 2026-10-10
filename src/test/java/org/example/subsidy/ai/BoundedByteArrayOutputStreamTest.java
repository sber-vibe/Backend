
package org.example.subsidy.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class BoundedByteArrayOutputStreamTest {

    @Test
    void acceptsDataWithinLimit() throws Exception {

        var output = new BoundedByteArrayOutputStream(4);

        output.write(new byte[]{1, 2, 3});
        output.write(4);

        assertArrayEquals(
                new byte[]{1, 2, 3, 4},
                output.toByteArray()
        );
    }

    @Test
    void rejectsDataExceedingLimit() throws Exception {

        var output = new BoundedByteArrayOutputStream(4);

        output.write(new byte[]{1, 2, 3});

        assertThrows(
                IOException.class,
                () -> output.write(new byte[]{4, 5})
        );

        assertArrayEquals(
                new byte[]{1, 2, 3},
                output.toByteArray()
        );
    }

    @Test
    void rejectsSingleByteBeyondLimit() throws Exception {

        var output = new BoundedByteArrayOutputStream(1);

        output.write(42);

        assertThrows(
                IOException.class,
                () -> output.write(43)
        );
    }
}
