package com.agripulse.app.controller;

import com.agripulse.app.dto.RiskAnalysisRequest;
import com.agripulse.app.dto.RiskAnalysisResponse;
import com.agripulse.app.service.AgriService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgriController.class)
class AgriControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgriService agriService;

    @Test
    @WithMockUser(username = "shaurya@example.com")
    void analyzeRiskReturnsSavedRiskReport() throws Exception {
        RiskAnalysisRequest request = new RiskAnalysisRequest();
        request.setCropName("Wheat");
        request.setRegion("Punjab");

        RiskAnalysisResponse response = new RiskAnalysisResponse();
        response.setId(1L);
        response.setCropName("Wheat");
        response.setRegion("Punjab");
        response.setRiskLevel("Medium");
        response.setMitigationStrategy("Use alternate suppliers and increase cold-storage planning.");

        when(agriService.analyzeRisk(any(RiskAnalysisRequest.class), anyString())).thenReturn(response);

        mockMvc.perform(post("/api/risk/analyze")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.cropName").value("Wheat"))
                .andExpect(jsonPath("$.region").value("Punjab"))
                .andExpect(jsonPath("$.riskLevel").value("Medium"))
                .andExpect(jsonPath("$.mitigationStrategy").exists());
    }
}
