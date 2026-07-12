package com.example.myllm.support.docforge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DocForgeClientTests {

    @Test
    void sanitizeFilenameFallsBackForMissingName() {
        assertEquals("unknown", DocForgeClient.sanitizeFilename(null));
        assertEquals("unknown", DocForgeClient.sanitizeFilename("  "));
    }

    @Test
    void sanitizeFilenameStripsPathAndReplacesLineBreaks() {
        assertEquals(
                "report__forged.pdf",
                DocForgeClient.sanitizeFilename(
                        "C:\\uploads\\report\r\nforged.pdf"));
    }
}
