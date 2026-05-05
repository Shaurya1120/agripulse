package com.agripulse.app.config;

import com.agripulse.app.service.EmergencyAlertTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// @Configuration tells Spring that this class contains bean definitions.
// A bean is simply an object that Spring creates and manages for us.
@Configuration
public class AiConfig {

    // @Bean tells Spring to store the returned object in the application context.
    // We build one ChatClient and register our alert tool so the AI can call it.
    @Bean
    public ChatClient agriChatClient(ChatClient.Builder chatClientBuilder, EmergencyAlertTool emergencyAlertTool) {
        return chatClientBuilder
                .defaultTools(emergencyAlertTool)
                .build();
    }
}

