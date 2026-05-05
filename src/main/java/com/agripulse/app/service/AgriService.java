package com.agripulse.app.service;

import com.agripulse.app.dto.AiRiskAssessment;
import com.agripulse.app.dto.HistoryPageResponse;
import com.agripulse.app.dto.RiskAnalysisRequest;
import com.agripulse.app.dto.RiskAnalysisResponse;
import com.agripulse.app.dto.RiskMapPoint;
import com.agripulse.app.dto.UiDashboardData;
import com.agripulse.app.model.RiskReport;
import com.agripulse.app.repository.RiskReportRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RequiredArgsConstructor
public class AgriService {

    private static final String SYSTEM_PROMPT = """
            You are an Agri-Business Expert.
            Analyze the supply chain risk for the given crop and location.
            Return a Risk Level, a detailed disruption explanation, and a Plan B strategy.
            If the risk level is High, call the sendEmergencyAlert tool before finalizing your answer.
            Keep the risk level limited to one of these values only: Low, Medium, High.
            The only tool you are allowed to call is sendEmergencyAlert.
            Never call any tool named analyzeSupplyChainRisk or any other tool name.
            Do not invent tools. If risk is not High, do not call any tool.
            Include practical detail about real disruption causes such as crop disease, heat wave, excess rainfall,
            flood risk, transport bottlenecks, market price swings, storage stress, and policy/export shocks when relevant.
            Tailor the answer to either an enterprise buyer or a farmer-producer based on stakeholderType.
            Support nearly any crop and nearly any location.
            Treat the provided location as exact user input. If the user gives a district, city, block, or state such as
            Malda, West Bengal, use that exact place in your reasoning instead of replacing it with a generic region.
            Use simple language that a new visitor can understand quickly.
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
                            Location: {region}
                            Stakeholder Type: {stakeholderType}
                            Quantity Tonnes: {quantityTonnes}
                            Crop Rate INR per Kg: {cropRatePerKgInr}
                            Farm Area Acres: {farmAreaAcres}
                            Planning Horizon Days: {planningHorizonDays}

                            Analyze the agricultural supply chain risk for this crop and exact location.
                            Return a detailed, structured answer with:
                            - riskLevel
                            - disruptionSummary
                            - primaryThreat
                            - detailedProblem
                            - riskFactors as a short list
                            - mitigationStrategy
                            - enterpriseActions as a short list
                            - farmerActions as a short list
                            - governmentSchemes as a short list relevant for India when useful
                            - expectedSupplyImpactPercent as an integer
                            - expectedPriceIncreasePercent as an integer
                            - estimatedLossPercent as an integer
                            Use simple language.
                            Clearly describe the actual local problem in that place.
                            If the risk is High, call the sendEmergencyAlert tool using the same cropName, region,
                            riskLevel, and mitigationStrategy before you complete the response.
                            Do not call analyzeSupplyChainRisk. That is not a real tool.
                            Return only the structured risk result for this request.
                            """)
                            .param("cropName", request.getCropName())
                            .param("region", request.getRegion())
                            .param("stakeholderType", normalizeStakeholderType(request))
                            .param("quantityTonnes", safeNumber(request.getQuantityTonnes()))
                            .param("cropRatePerKgInr", safeNumber(request.getCropRatePerKgInr()))
                            .param("farmAreaAcres", safeNumber(request.getFarmAreaAcres()))
                            .param("planningHorizonDays", request.getPlanningHorizonDays() == null ? "Not provided" : request.getPlanningHorizonDays()))
                    .call()
                    .entity(AiRiskAssessment.class);

            RiskReport riskReport = new RiskReport();
            riskReport.setCropName(request.getCropName().trim());
            riskReport.setRegion(request.getRegion().trim());
            riskReport.setRiskLevel(normalizeRiskLevel(aiRiskAssessment));
            riskReport.setMitigationStrategy(normalizeMitigationStrategy(aiRiskAssessment));

            RiskReport savedRiskReport = riskReportRepository.save(riskReport);
            BigDecimal estimatedLossInr = estimateLossInr(request, aiRiskAssessment);

            return new RiskAnalysisResponse(
                    savedRiskReport.getId(),
                    savedRiskReport.getCropName(),
                    savedRiskReport.getRegion(),
                    savedRiskReport.getRiskLevel(),
                    savedRiskReport.getMitigationStrategy(),
                    normalizeStakeholderType(request),
                    defaultText(aiRiskAssessment.getDisruptionSummary(), "AgriPulse identified meaningful disruption pressure in this corridor."),
                    defaultText(aiRiskAssessment.getPrimaryThreat(), "Supply chain volatility"),
                    defaultText(aiRiskAssessment.getDetailedProblem(), "The model did not return a full disruption explanation."),
                    normalizeList(aiRiskAssessment.getRiskFactors()),
                    normalizeList(aiRiskAssessment.getEnterpriseActions()),
                    normalizeList(aiRiskAssessment.getFarmerActions()),
                    normalizeList(aiRiskAssessment.getGovernmentSchemes()),
                    normalizePercent(aiRiskAssessment.getExpectedSupplyImpactPercent()),
                    normalizePercent(aiRiskAssessment.getExpectedPriceIncreasePercent()),
                    normalizePercent(aiRiskAssessment.getEstimatedLossPercent()),
                    request.getQuantityTonnes(),
                    request.getCropRatePerKgInr(),
                    request.getFarmAreaAcres(),
                    request.getPlanningHorizonDays(),
                    estimatedLossInr
            );
        }
        catch (NonTransientAiException exception) {
            log.warn(
                    "AI provider request failed for crop='{}', region='{}'. type={}, message={}",
                    request.getCropName(),
                    request.getRegion(),
                    exception.getClass().getSimpleName(),
                    summarizeException(exception)
            );
            throw new IllegalStateException("The AI provider is temporarily unavailable. Please try again shortly.", exception);
        }
        catch (DataAccessResourceFailureException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            log.error(
                    "Unexpected AI workflow failure for crop='{}', region='{}'. type={}, message={}",
                    request.getCropName(),
                    request.getRegion(),
                    exception.getClass().getSimpleName(),
                    summarizeException(exception)
            );
            throw exception;
        }
    }

    private String summarizeException(Throwable throwable) {
        Throwable cursor = throwable;
        String lastMessage = throwable.getMessage();

        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
            if (StringUtils.hasText(cursor.getMessage())) {
                lastMessage = cursor.getMessage();
            }
        }

        return StringUtils.hasText(lastMessage) ? lastMessage : "No provider message returned.";
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

    private String normalizeStakeholderType(RiskAnalysisRequest request) {
        if (request == null || !StringUtils.hasText(request.getStakeholderType())) {
            return "Enterprise";
        }

        String normalized = request.getStakeholderType().trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "farmer", "producer" -> "Farmer";
            default -> "Enterprise";
        };
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private List<String> normalizeList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        return items.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .limit(6)
                .toList();
    }

    private Integer normalizePercent(Integer value) {
        if (value == null) {
            return null;
        }

        return Math.max(0, Math.min(value, 100));
    }

    private String safeNumber(BigDecimal value) {
        return value == null ? "Not provided" : value.stripTrailingZeros().toPlainString();
    }

    private BigDecimal estimateLossInr(RiskAnalysisRequest request, AiRiskAssessment aiRiskAssessment) {
        if (request == null || request.getQuantityTonnes() == null || request.getCropRatePerKgInr() == null) {
            return null;
        }

        Integer lossPercent = normalizePercent(aiRiskAssessment.getEstimatedLossPercent());
        if (lossPercent == null) {
            return null;
        }

        return request.getQuantityTonnes()
                .multiply(BigDecimal.valueOf(1000))
                .multiply(request.getCropRatePerKgInr())
                .multiply(BigDecimal.valueOf(lossPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
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
