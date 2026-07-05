package com.example.myllm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时探测 embedding 维度，与配置期望值对齐。 */
@Component
public class EmbeddingDimensionValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingDimensionValidator.class);

    private final EmbeddingModel embeddingModel;
    private final int expectedDimension;
    private final boolean enabled;

    public EmbeddingDimensionValidator(
            EmbeddingModel embeddingModel,
            @Value("${rag.embedding.expected-dimension:1024}") int expectedDimension,
            @Value("${rag.embedding.validate-dimension-on-startup:true}") boolean enabled) {
        this.embeddingModel = embeddingModel;
        this.expectedDimension = expectedDimension;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || expectedDimension <= 0) {
            return;
        }
        try {
            float[] probe = embeddingModel.embed("dimension-probe");
            if (probe.length == 0) {
                log.warn("embedding 维度探测失败：模型返回空向量");
                return;
            }
            if (probe.length != expectedDimension) {
                throw new IllegalStateException("embedding 维度不匹配：期望 "
                        + expectedDimension + "，实际 " + probe.length);
            }
            for (float value : probe) {
                if (!Float.isFinite(value)) {
                    throw new IllegalStateException("embedding 探测结果包含非有限数值");
                }
            }
            log.info("embedding 维度探测通过 dimension={}", probe.length);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("embedding 维度探测跳过（模型可能未就绪）: {}", e.getMessage());
        }
    }
}
