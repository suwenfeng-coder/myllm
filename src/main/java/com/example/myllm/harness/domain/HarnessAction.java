package com.example.myllm.harness.domain;

import java.util.List;
import java.util.Map;

/** 模型结构化输出：调用工具或给出最终答案。 */
public sealed interface HarnessAction permits HarnessAction.CallTool, HarnessAction.Final {

    enum Kind {
        CALL_TOOL,
        FINAL
    }

    Kind kind();

    String summary();

    record CallTool(String tool, Map<String, Object> arguments, String summary) implements HarnessAction {

        public CallTool {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
            summary = summary == null ? "" : summary;
        }

        @Override
        public Kind kind() {
            return Kind.CALL_TOOL;
        }
    }

    record Citation(String sourceId, String excerpt) {
    }

    record Final(String answer, List<Citation> citations, String summary) implements HarnessAction {

        public Final {
            citations = citations == null ? List.of() : List.copyOf(citations);
            summary = summary == null ? "" : summary;
        }

        @Override
        public Kind kind() {
            return Kind.FINAL;
        }
    }
}
