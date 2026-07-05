package com.example.myllm.harness.adapter.model;

import com.example.myllm.harness.domain.HarnessActionParser;
import com.example.myllm.harness.port.ModelGateway;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 确定性 Fixture 回放网关，按序返回预置 JSON 响应。
 *
 * <p>用于单测与 Replay 验收，不调用真实 LLM。</p>
 */
public class FixtureReplayModelGateway implements ModelGateway {

    private final HarnessActionParser actionParser;
    private volatile List<String> scriptedResponses;
    private final int inputTokensPerCall;
    private final int outputTokensPerCall;
    private final AtomicInteger cursor = new AtomicInteger(0);

    public FixtureReplayModelGateway(
            HarnessActionParser actionParser,
            List<String> scriptedResponses,
            int inputTokensPerCall,
            int outputTokensPerCall) {
        this.actionParser = actionParser;
        this.scriptedResponses = scriptedResponses == null ? List.of() : List.copyOf(scriptedResponses);
        this.inputTokensPerCall = Math.max(0, inputTokensPerCall);
        this.outputTokensPerCall = Math.max(0, outputTokensPerCall);
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        int index = cursor.getAndIncrement();
        if (index >= scriptedResponses.size()) {
            return ModelResponse.failed("Fixture 回放已耗尽，索引=" + index);
        }
        String raw = scriptedResponses.get(index);
        HarnessActionParser.ParseResult parsed = actionParser.parse(raw);
        if (!parsed.success()) {
            return ModelResponse.failed(parsed.errorMessage());
        }
        return ModelResponse.ok(raw, parsed.action(), inputTokensPerCall, outputTokensPerCall);
    }

    public int cursor() {
        return cursor.get();
    }

    public void reset() {
        cursor.set(0);
    }

    /** 替换脚本并归零游标，供不同 Replay 场景隔离使用。 */
    public void reset(List<String> responses) {
        scriptedResponses = responses == null ? List.of() : List.copyOf(responses);
        cursor.set(0);
    }
}
