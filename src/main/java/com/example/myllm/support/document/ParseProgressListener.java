package com.example.myllm.support.document;

/** 文档解析阶段进度回调。 */
@FunctionalInterface
public interface ParseProgressListener {

    ParseProgressListener NOOP = (percent, message, etaSeconds, docforgeJobId) -> {};

    void onParseProgress(int percentWithinParsing, String message, Long etaSeconds, String docforgeJobId);

    static ParseProgressListener disabled() {
        return NOOP;
    }
}
