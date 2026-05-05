package com.agripulse.app.service;

import com.agripulse.app.dto.AiRiskAssessment;
import com.agripulse.app.dto.RiskAnalysisRequest;
import com.agripulse.app.dto.RiskAnalysisResponse;
import com.agripulse.app.model.RiskReport;
import com.agripulse.app.model.UserAccount;
import com.agripulse.app.repository.RiskReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgriServiceTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec chatClientRequestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    @Mock
    private RiskReportRepository riskReportRepository;

    @Mock
    private MandiPriceService mandiPriceService;

    @Mock
    private UserAccountService userAccountService;

    private EmergencyAlertTool emergencyAlertTool;
    private AgriService agriService;
    private UserAccount userAccount;

    @BeforeEach
    void setUp() {
        emergencyAlertTool = new EmergencyAlertTool();
        agriService = new AgriService(chatClient, riskReportRepository, emergencyAlertTool, mandiPriceService, userAccountService);
        userAccount = new UserAccount();
        userAccount.setId(1L);
        userAccount.setFullName("Shaurya Rathi");
        userAccount.setEmail("shaurya@example.com");
        when(userAccountService.getRequiredUser("shaurya@example.com")).thenReturn(userAccount);
    }

    @Test
    void analyzeRiskPersistsNormalizedAiResponse() {
        RiskAnalysisRequest request = new RiskAnalysisRequest();
        request.setCropName("Wheat");
        request.setRegion("Punjab");
        request.setStakeholderType("enterprise");

        AiRiskAssessment aiRiskAssessment = new AiRiskAssessment();
        aiRiskAssessment.setRiskLevel("high");
        aiRiskAssessment.setMitigationStrategy("Shift part of sourcing to a cooler nearby region and hedge transport costs.");
        aiRiskAssessment.setDisruptionSummary("Heat and logistics pressure are tightening the wheat corridor.");
        aiRiskAssessment.setPrimaryThreat("Heat wave");
        aiRiskAssessment.setDetailedProblem("Heat stress is reducing quality and raising storage risk.");

        when(chatClient.prompt()).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.system(anyString())).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.user(any(Consumer.class))).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(AiRiskAssessment.class)).thenReturn(aiRiskAssessment);
        when(mandiPriceService.findMarketEvidence(anyString(), anyString(), any(), anyString()))
                .thenReturn(MandiPriceService.MarketEvidence.unavailable());
        when(riskReportRepository.save(any(RiskReport.class))).thenAnswer(invocation -> {
            RiskReport saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        RiskAnalysisResponse response = agriService.analyzeRisk(request, "shaurya@example.com");

        ArgumentCaptor<RiskReport> riskReportCaptor = ArgumentCaptor.forClass(RiskReport.class);
        org.mockito.Mockito.verify(riskReportRepository).save(riskReportCaptor.capture());

        RiskReport savedRiskReport = riskReportCaptor.getValue();

        assertThat(savedRiskReport.getCropName()).isEqualTo("Wheat");
        assertThat(savedRiskReport.getRegion()).isEqualTo("Punjab");
        assertThat(savedRiskReport.getRiskLevel()).isEqualTo("High");
        assertThat(savedRiskReport.getMitigationStrategy()).contains("Shift part of sourcing");
        assertThat(savedRiskReport.getUserAccount()).isEqualTo(userAccount);

        assertThat(response.getId()).isEqualTo(99L);
        assertThat(response.getRiskLevel()).isEqualTo("High");
        assertThat(response.getRegion()).isEqualTo("Punjab");
    }

    @Test
    void analyzeRiskUsesLiveWeatherEvidenceForCalmConditions() {
        RiskAnalysisRequest request = new RiskAnalysisRequest();
        request.setCropName("Wheat");
        request.setRegion("Malda, West Bengal");
        request.setStakeholderType("enterprise");
        request.setWeatherContext("Malda, West Bengal, India | 29 C | Wind 8 km/h | code 1");

        AiRiskAssessment aiRiskAssessment = new AiRiskAssessment();
        aiRiskAssessment.setRiskLevel("high");
        aiRiskAssessment.setMitigationStrategy("Keep watching the corridor.");
        aiRiskAssessment.setDisruptionSummary("The model attempted to signal elevated pressure.");
        aiRiskAssessment.setPrimaryThreat("Heat wave");
        aiRiskAssessment.setDetailedProblem("The model returned a severe problem.");

        when(chatClient.prompt()).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.system(anyString())).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.user(any(Consumer.class))).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(AiRiskAssessment.class)).thenReturn(aiRiskAssessment);
        when(mandiPriceService.findMarketEvidence(anyString(), anyString(), any(), anyString()))
                .thenReturn(MandiPriceService.MarketEvidence.unavailable());
        when(riskReportRepository.save(any(RiskReport.class))).thenAnswer(invocation -> {
            RiskReport saved = invocation.getArgument(0);
            saved.setId(101L);
            return saved;
        });

        RiskAnalysisResponse response = agriService.analyzeRisk(request, "shaurya@example.com");

        assertThat(response.getRiskLevel()).isEqualTo("No Risk");
        assertThat(response.getPrimaryThreat()).isEqualTo("Stable weather conditions");
        assertThat(response.getDisruptionSummary()).contains("No strong live weather disruption");
        assertThat(response.getEstimatedLossPercent()).isZero();
    }

    @Test
    void analyzeRiskUsesVerifiedMandiSignalForPricePressure() {
        RiskAnalysisRequest request = new RiskAnalysisRequest();
        request.setCropName("Wheat");
        request.setRegion("Malda, West Bengal");
        request.setStakeholderType("enterprise");
        request.setWeatherContext("Malda, West Bengal, India | 29 C | Wind 8 km/h | code 1");
        request.setQuantityTonnes(new BigDecimal("10"));
        request.setCropRatePerKgInr(new BigDecimal("20"));

        AiRiskAssessment aiRiskAssessment = new AiRiskAssessment();
        aiRiskAssessment.setRiskLevel("low");
        aiRiskAssessment.setMitigationStrategy("Shift volume carefully.");
        aiRiskAssessment.setDisruptionSummary("Light pressure.");
        aiRiskAssessment.setPrimaryThreat("Stable weather conditions");
        aiRiskAssessment.setDetailedProblem("The AI sees only mild disruption.");

        when(chatClient.prompt()).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.system(anyString())).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.user(any(Consumer.class))).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(AiRiskAssessment.class)).thenReturn(aiRiskAssessment);
        when(mandiPriceService.findMarketEvidence(anyString(), anyString(), any(), anyString()))
                .thenReturn(new MandiPriceService.MarketEvidence(
                        true,
                        "Wheat",
                        "Malda Mandi, Malda, West Bengal",
                        "05/05/2026",
                        new BigDecimal("30.00"),
                        new BigDecimal("28.00"),
                        new BigDecimal("32.00"),
                        "Very High",
                        50,
                        18,
                        25,
                        "Official mandi data from Malda Mandi, Malda, West Bengal shows Wheat near INR 30 per kg on 2026-05-05.",
                        java.util.List.of("Verified mandi modal price is INR 30 per kg."),
                        "data.gov.in AGMARKNET mandi price dataset"
                ));
        when(riskReportRepository.save(any(RiskReport.class))).thenAnswer(invocation -> {
            RiskReport saved = invocation.getArgument(0);
            saved.setId(111L);
            return saved;
        });

        RiskAnalysisResponse response = agriService.analyzeRisk(request, "shaurya@example.com");

        assertThat(response.getRiskLevel()).isEqualTo("Very High");
        assertThat(response.getExpectedPriceIncreasePercent()).isEqualTo(50);
        assertThat(response.getEstimatedLossInr()).isEqualByComparingTo("75000.00");
    }
}
