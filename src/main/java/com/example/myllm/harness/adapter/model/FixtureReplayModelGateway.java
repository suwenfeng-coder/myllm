package com.example.myllm.harness.adapter.model;

import com.example.myllm.harness.domain.HarnessActionParser;
import com.example.myllm.harness.port.ModelGateway;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 确定性 Fixture 回放网关，按序返回预置 JSON 响应。
 *
 * <p>用于单测与 Replay 验收，不调用真实 LLM。</p>
 */
public class FixtureReplayModelGateway implements ModelGateway {

    private final HarnessActionParser actionParser;
    private final int inputTokensPerCall;
    private final int outputTokensPerCall;
    private final AtomicReference<ReplayState> state;

    public FixtureReplayModelGateway(
            HarnessActionParser actionParser,
            List<String> scriptedResponses,
            int inputTokensPerCall,
            int outputTokensPerCall) {
        this.actionParser = actionParser;
        this.state = new AtomicReference<>(new ReplayState(copyResponses(scriptedResponses), 0));
        this.inputTokensPerCall = Math.max(0, inputTokensPerCall);
        this.outputTokensPerCall = Math.max(0, outputTokensPerCall);
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        ScriptedResponse scripted = nextResponse();
        if (scripted.exhausted()) {
            return ModelResponse.failed("Fixture 回放已耗尽，索引=" + scripted.index());
        }
        String raw = scripted.raw();
        HarnessActionParser.ParseResult parsed = actionParser.parse(raw);
        if (!parsed.success()) {
            return ModelResponse.failed(parsed.errorMessage());
        }
        return ModelResponse.ok(raw, parsed.action(), inputTokensPerCall, outputTokensPerCall);
    }

    public int cursor() {
        return state.get().cursor();
    }

    public void reset() {
        state.updateAndGet(current -> new ReplayState(current.responses(), 0));
    }

    /** 替换脚本并归零游标，供不同 Replay 场景隔离使用。 */
    public void reset(List<String> responses) {
        state.set(new ReplayState(copyResponses(responses), 0));
    }

    private ScriptedResponse nextResponse() {
        while (true) {
            ReplayState current = state.get();
            int index = current.cursor();
            if (index >= current.responses().size()) {
                return ScriptedResponse.exhausted(index);
            }
            ReplayState next = new ReplayState(current.responses(), index + 1);
            if (state.compareAndSet(current, next)) {
                return ScriptedResponse.value(index, current.responses().get(index));
            }
        }
    }

    private static List<String> copyResponses(List<String> responses) {
        return responses == null ? List.of() : List.copyOf(responses);
    }

    private record ReplayState(List<String> responses, int cursor) {}

    private record ScriptedResponse(int index, String raw, boolean exhausted) {
        static ScriptedResponse value(int index, String raw) {
            return new ScriptedResponse(index, raw, false);
        }

        static ScriptedResponse exhausted(int index) {
            return new ScriptedResponse(index, null, true);
        }
    }
}
