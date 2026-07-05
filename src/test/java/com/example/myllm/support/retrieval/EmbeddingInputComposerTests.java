package com.example.myllm.support.retrieval;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmbeddingInputComposerTests {

    @Test
    void composePrefixesSourceFileName() {
        String result = EmbeddingInputComposer.compose(
                "《汽车用户手册（2023年版）》.docx", "第4章：车辆基本操作");

        assertTrue(result.startsWith("来源文件：《汽车用户手册（2023年版）》.docx"));
        assertTrue(result.contains("第4章：车辆基本操作"));
    }
}
