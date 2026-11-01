package dev.example.productai.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagIndexerSourceTypeTest {

    @Test
    void indexesJavaAndApisixLuaOnly() {
        assertTrue(RagIndexer.isIndexableSource(Path.of("Demo.java")));
        assertTrue(RagIndexer.isIndexableSource(Path.of("header-token-interceptor.lua")));
        assertTrue(RagIndexer.isIndexableSource(Path.of("PLUGIN.LUA")));
        assertFalse(RagIndexer.isIndexableSource(Path.of("application.yml")));
        assertFalse(RagIndexer.isIndexableSource(Path.of("README.md")));
    }
}
