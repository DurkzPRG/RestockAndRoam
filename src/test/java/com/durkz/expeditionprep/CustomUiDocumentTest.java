package com.durkz.expeditionprep;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.fail;

class CustomUiDocumentTest {
    @Test void documentsOnlyUseSupportedStringEscapes() throws Exception {
        try (var files = Files.walk(Path.of("src/main/resources/Common/UI/Custom"))) {
            for (var path : files.filter(p -> p.toString().endsWith(".ui")).toList()) {
                String text = Files.readString(path);
                boolean quoted = false;
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (quoted && c == '\\') {
                        if (++i >= text.length() || (text.charAt(i) != '\\' && text.charAt(i) != '"')) {
                            fail("Unsupported CustomUI string escape in " + path + " at offset " + i);
                        }
                    } else if (c == '"') quoted = !quoted;
                }
                if (quoted) fail("Unterminated string in " + path);
            }
        }
    }
}
