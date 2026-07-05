package com.example.myllm.harness.port;

import java.util.Collection;
import java.util.Optional;

/** 已注册工具查找表。 */
public interface ToolRegistry {

    void register(HarnessTool<?, ?> tool);

    Optional<HarnessTool<?, ?>> find(String toolName);

    Collection<ToolDescriptor> listDescriptors();

    boolean contains(String toolName);
}
