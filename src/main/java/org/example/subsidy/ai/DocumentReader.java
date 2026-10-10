
package org.example.subsidy.ai;

import java.io.IOException;

public interface DocumentReader {

    DocumentContent read(
            byte[] data,
            String filename
    ) throws IOException;
}
