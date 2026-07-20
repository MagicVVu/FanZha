package com.magicvvu.fanzha.backend.service.fraud.deepseek;

import com.magicvvu.fanzha.backend.config.DeepSeekConfig;
import com.magicvvu.fanzha.backend.config.FraudPipelineProperties;
import com.magicvvu.fanzha.backend.util.DeepSeekClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

public class DeepSeekFraudCaseExtractorTest {

    @Test
    public void extractsJsonFromText() throws Exception {
        RestTemplate fakeRestTemplate = new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> postForEntity(String url, Object request, Class<T> responseType, Object... uriVariables) {
                if (responseType != DeepSeekClient.ChatCompletionsResponse.class) {
                    throw new IllegalArgumentException("unexpected responseType: " + responseType);
                }

                DeepSeekClient.ChatMessage message = new DeepSeekClient.ChatMessage(
                        "assistant",
                        "```json\n{\"caseStatus\":\"已立案\",\"credibilityScore\":0.9}\n```"
                );
                DeepSeekClient.Choice choice = new DeepSeekClient.Choice();
                choice.setMessage(message);
                DeepSeekClient.ChatCompletionsResponse body = new DeepSeekClient.ChatCompletionsResponse();
                body.setChoices(Collections.singletonList(choice));
                return ResponseEntity.ok(responseType.cast(body));
            }
        };

        DeepSeekConfig.DeepSeekProperties deepSeekProperties = new DeepSeekConfig.DeepSeekProperties();
        deepSeekProperties.setApiKey("test-key");
        deepSeekProperties.setBaseUrl("https://api.deepseek.com");
        deepSeekProperties.setModel("deepseek-chat");
        DeepSeekClient client = new DeepSeekClient(fakeRestTemplate, deepSeekProperties);

        FraudPipelineProperties props = new FraudPipelineProperties();
        props.setEnabled(true);
        props.setMaxRetries(2);

        DeepSeekFraudCaseExtractor extractor = new DeepSeekFraudCaseExtractor(client, props);
        FraudCaseExtraction extraction = extractor.extract("t", "c");
        Assertions.assertNotNull(extraction);
        Assertions.assertEquals("已立案", extraction.getCaseStatus());
        Assertions.assertEquals(0.9d, extraction.getCredibilityScore(), 1e-6);
    }
}
