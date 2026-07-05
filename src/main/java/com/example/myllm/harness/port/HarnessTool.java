package com.example.myllm.harness.port;

/** Harness 工具统一接口；由 Adapter 包装现有业务 Service。 */
public interface HarnessTool<I, O> {

    ToolDescriptor descriptor();

    Class<I> inputType();

    ToolResult<O> execute(ToolExecutionContext context, I input);
}
