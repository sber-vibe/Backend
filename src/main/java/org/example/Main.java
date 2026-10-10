
package org.example;

import org.example.subsidy.ai.DocumentParserWorker;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Main {

    public static void main(String[] args) {

        if (args.length == 4
                && "--ai-parser-worker".equals(args[0])) {

            int exitCode = DocumentParserWorker.run(
                    args[1],
                    args[2],
                    args[3]
            );

            System.exit(exitCode);
            return;
        }

        SpringApplication.run(Main.class, args);
    }
}
