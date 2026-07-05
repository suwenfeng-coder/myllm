package com.example.myllm.harness.application;

import com.example.myllm.harness.port.HarnessTool;
import com.example.myllm.harness.port.ToolDescriptor;
import com.example.myllm.harness.port.ToolRegistry;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 内存工具注册表，启动时收集所有 {@link HarnessTool} Bean。 */
@Component
public class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, HarnessTool<?, ?>> tools = new LinkedHashMap<>();

    public DefaultToolRegistry(List<HarnessTool<?, ?>> discoveredTools) {
        if (discoveredTools != null) {
            for (HarnessTool<?, ?> tool : discoveredTools) {
                register(tool);
            }
        }
    }

    @Override
    public void register(HarnessTool<?, ?> tool) {
        if (tool == null || tool.descriptor() == null) {
            throw new IllegalArgumentException("tool 与 descriptor 不能为空");
        }
        String name = tool.descriptor().name();
        if (tools.containsKey(name)) {
            throw new IllegalStateException("重复注册工具: " + name);
        }
        tools.put(name, tool);
    }

    @Override
    public Optional<HarnessTool<?, ?>> find(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tools.get(toolName.trim()));
    }

    @Override
    public Collection<ToolDescriptor> listDescriptors() {
        return tools.values().stream().map(HarnessTool::descriptor).toList();
    }

    @Override
    public boolean contains(String toolName) {
        return find(toolName).isPresent();
    }
}
