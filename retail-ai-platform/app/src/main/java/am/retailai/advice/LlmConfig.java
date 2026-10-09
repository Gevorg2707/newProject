package am.retailai.advice;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.OutputConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * LLM is opt-in: llm.enabled=true plus Anthropic credentials (ANTHROPIC_API_KEY or `ant auth login` profile).
 * Default model claude-opus-5-5 at effort low: the task is a short rewrite of given facts.
 * Before enabling for a real client: confirm with the lawyer the cross-border transfer basis (Art. 27, RA personal data law),
 * even though only aggregates and product data are sent.
 */
@Configuration
public class LlmConfig {

    @Bean
    GuardedExplainer guardedExplainer(@Value("${llm.enabled:false}") boolean enabled,
                                      @Value("${llm.model:claude-opus-5-5}") String model,
                                      @Value("${llm.effort:low}") String effort,
                                      ObjectMapper json) {
        if (!enabled) {
            return new GuardedExplainer(null);
        }
        var client = AnthropicOkHttpClient.fromEnv();
        var e = OutputConfig.Effort.of(effort.toLowerCase(Locale.ROOT));
        return new GuardedExplainer(new ClaudeExplanationProvider(client, model, e, json));
    }
}
