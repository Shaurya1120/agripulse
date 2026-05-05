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
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    void analyzeRiskReturnsSavedRiskReport() throws Exception {
        RiskAnalysisRequest request = new RiskAnalysisRequest("Wheat", "Punjab");
        RiskAnalysisResponse response = new RiskAnalysisResponse(
                1L,
                "Wheat",
                "Punjab",
                "Medium",
                "Use alternate suppliers and increase cold-storage planning."
        );

        when(agriService.analyzeRisk(any(RiskAnalysisRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/risk/analyze")
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
