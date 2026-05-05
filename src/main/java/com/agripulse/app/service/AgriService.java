package com.agripulse.app.service;

import com.agripulse.app.dto.AiRiskAssessment;
import com.agripulse.app.dto.HistoryPageResponse;
import com.agripulse.app.dto.RiskAnalysisRequest;
import com.agripulse.app.dto.RiskAnalysisResponse;
import com.agripulse.app.dto.RiskMapPoint;
import com.agripulse.app.dto.UiDashboardData;
import com.agripulse.app.model.RiskReport;
import com.agripulse.app.repository.RiskReportRepository;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

// @Service marks this class as business logic.
// Controllers should stay thin and delegate real work to services like this one.
@Service
@RequiredArgsConstructor
public class AgriService {

    private static final String SYSTEM_PROMPT = """
            You are an Agri-Business Expert.
            Analyze the supply chain risk for the given crop and region.
            Return a Risk Level and a Plan B strategy.
            If the risk level is High, call the sendEmergencyAlert tool before finalizing your answer.
            Keep the risk level limited to one of these values only: Low, Medium, High.
            """;

    private final ChatClient agriChatClient;
    private final RiskReportRepository riskReportRepository;

    public RiskAnalysisResponse analyzeRisk(RiskAnalysisRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }

        try {
            AiRiskAssessment aiRiskAssessment = agriChatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userSpec -> userSpec.text("""
                            Crop Name: {cropName}
                            Region: {region}

                            Analyze the agricultural supply chain risk for this crop and region.
                            Return a short mitigation strategy that a business user can act on.
                            If the risk is High, call the sendEmergencyAlert tool using the same cropName, region,
                            riskLevel, and mitigationStrategy before you complete the response.
                            """)
                            .param("cropName", request.getCropName())
                            .param("region", request.getRegion()))
                    .call()
                    .entity(AiRiskAssessment.class);

            RiskReport riskReport = new RiskReport();
            riskReport.setCropName(request.getCropName().trim());
            riskReport.setRegion(request.getRegion().trim());
            riskReport.setRiskLevel(normalizeRiskLevel(aiRiskAssessment));
            riskReport.setMitigationStrategy(normalizeMitigationStrategy(aiRiskAssessment));

            RiskReport savedRiskReport = riskReportRepository.save(riskReport);
            return RiskAnalysisResponse.fromEntity(savedRiskReport);
        }
        catch (NonTransientAiException exception) {
            throw new IllegalStateException("The AI provider is temporarily unavailable. Please try again shortly.", exception);
        }
        catch (DataAccessResourceFailureException exception) {
            throw exception;
        }
    }

    private String normalizeRiskLevel(AiRiskAssessment aiRiskAssessment) {
        if (aiRiskAssessment == null || !StringUtils.hasText(aiRiskAssessment.getRiskLevel())) {
            throw new IllegalStateException("The AI response did not include a risk level.");
        }

        String rawRiskLevel = aiRiskAssessment.getRiskLevel().trim().toLowerCase(Locale.ROOT);

        return switch (rawRiskLevel) {
            case "low" -> "Low";
            case "medium", "med", "moderate" -> "Medium";
            case "high", "severe", "critical" -> "High";
            default -> throw new IllegalStateException("Unexpected risk level returned by the AI: " + aiRiskAssessment.getRiskLevel());
        };
    }

    private String normalizeMitigationStrategy(AiRiskAssessment aiRiskAssessment) {
        if (aiRiskAssessment == null || !StringUtils.hasText(aiRiskAssessment.getMitigationStrategy())) {
            return "No mitigation strategy was returned by the AI model.";
        }

        return aiRiskAssessment.getMitigationStrategy().trim();
    }

    public HistoryPageResponse getHistoryPage(int page, int size) {
        int sanitizedPage = Math.max(page, 0);
        int sanitizedSize = Math.min(Math.max(size, 1), 20);

        Page<RiskReport> reports = riskReportRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(sanitizedPage, sanitizedSize));

        return new HistoryPageResponse(
                reports.getContent().stream().map(RiskAnalysisResponse::fromEntity).toList(),
                reports.getNumber(),
                reports.getSize(),
                reports.getTotalElements(),
                reports.getTotalPages()
        );
    }

    public UiDashboardData getDashboardData() {
        Page<RiskReport> recentReportsPage = riskReportRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 50));
        var recentReports = recentReportsPage.getContent();

        Map<String, java.util.List<RiskReport>> groupedByRegion = recentReports.stream()
                .collect(Collectors.groupingBy(RiskReport::getRegion));

        var mapPoints = groupedByRegion.entrySet().stream()
                .map(entry -> {
                    RiskReport latest = entry.getValue().stream()
                            .max(Comparator.comparing(RiskReport::getCreatedAt))
                            .orElseThrow();
                    long highRiskCount = entry.getValue().stream()
                            .filter(report -> "High".equalsIgnoreCase(report.getRiskLevel()))
                            .count();

                    return new RiskMapPoint(
                            entry.getKey(),
                            entry.getValue().size(),
                            highRiskCount,
                            latest.getRiskLevel()
                    );
                })
                .sorted(Comparator.comparing(RiskMapPoint::getHighRiskReports).reversed())
                .toList();

        return new UiDashboardData(
                mapPoints,
                recentReports.stream().limit(10).map(RiskAnalysisResponse::fromEntity).toList()
        );
    }
}
