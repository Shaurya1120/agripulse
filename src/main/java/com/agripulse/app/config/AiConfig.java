package com.agripulse.app.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// @Configuration tells Spring that this class contains bean definitions.
// A bean is simply an object that Spring creates and manages for us.
@Configuration
public class AiConfig {

    // @Bean tells Spring to store the returned object in the application context.
    // We build one ChatClient for plain structured generation.
    @Bean
    public ChatClient agriChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }
}
