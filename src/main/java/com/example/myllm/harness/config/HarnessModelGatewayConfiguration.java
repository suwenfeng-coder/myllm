package com.example.myllm.harness.config;

import com.example.myllm.harness.adapter.model.FixtureReplayModelGateway;
import com.example.myllm.harness.domain.HarnessActionParser;
import com.example.myllm.harness.port.ModelGateway;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "harness.enabled", havingValue = "true")
public class HarnessModelGatewayConfiguration {

    @Bean
    @ConditionalOnProperty(name = "harness.model.gateway", havingValue = "fixture")
    ModelGateway fixtureReplayModelGateway(HarnessActionParser actionParser, HarnessProperties properties) {
        HarnessProperties.Model model = properties.getModel();
        return new FixtureReplayModelGateway(
                actionParser,
                List.of(),
                model.getFixtureInputTokens(),
                model.getFixtureOutputTokens());
    }
}
