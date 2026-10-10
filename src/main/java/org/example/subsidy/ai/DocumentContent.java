
package org.example.subsidy.ai;

import java.util.List;

public record DocumentContent(
        String text,
        List<byte[]> images
) {
}
