package com.example.myllm.support.graph;

import java.text.Normalizer;
import java.util.Locale;

/** 实体名称归一化：Unicode NFKC、空白折叠、大小写不敏感比较键。 */
public final class EntityNameNormalizer {

    private EntityNameNormalizer() {
    }

    public static String normalize(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String nfkc = Normalizer.normalize(name, Normalizer.Form.NFKC);
        return nfkc.replaceAll("\\s+", " ").trim();
    }

    public static String entityKey(GraphEntityType type, String normalizedName) {
        return type.name() + ":" + normalizedName.toLowerCase(Locale.ROOT);
    }
}
