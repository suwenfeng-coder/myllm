package com.example.myllm.support.parser;

import com.example.myllm.support.docforge.DocForgeClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnBean(DocForgeClient.class)
public class DocForgeParserConfig {

    @Bean
    DocForgeRemoteDocumentParser doclingRemoteDocumentParser(DocForgeClient docForgeClient) {
        return new DocForgeRemoteDocumentParser(docForgeClient, "docling", "docling");
    }

    @Bean
    DocForgeRemoteDocumentParser makerRemoteDocumentParser(DocForgeClient docForgeClient) {
        return new DocForgeRemoteDocumentParser(docForgeClient, "maker", "maker");
    }
}
