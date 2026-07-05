package com.example.myllm.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.List;

/** 图能力关闭时的空实现，保证 RAG 主链路可正常注入。 */
@Service
@ConditionalOnProperty(prefix = "graph", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledGraphRetrievalPort implements GraphRetrievalPort {

    @Override
    public GraphSearchResult search(String query, List<String> fileIds) {
        return GraphSearchResult.unavailable("graph.disabled");
    }
}
