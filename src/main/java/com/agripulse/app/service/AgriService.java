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
import java.util.Arrays;
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
            If the risk level is High or Very High, call the sendEmergencyAlert tool before finalizing your answer.
            Keep the risk level limited to one of these values only: No Risk, Low, Medium, High, Very High.
            The only tool you are allowed to call is sendEmergencyAlert.
            Never call any tool named analyzeSupplyChainRisk or any other tool name.
            Do not invent tools. If risk is not High or Very High, do not call any tool.
            Include practical detail about real disruption causes such as crop disease, heat wave, excess rainfall,
            flood risk, transport bottlenecks, market price swings, storage stress, and policy/export shocks when relevant.
            Tailor the answer to either an enterprise buyer or a farmer-producer based on stakeholderType.
            Support nearly any crop and nearly any location.
            Treat the provided location as exact user input. If the user gives a district, city, block, or state such as
            Malda, West Bengal, use that exact place in your reasoning instead of replacing it with a generic region.
            Use simple language that a new visitor can understand quickly.
            For enterprise buyers and exporters, avoid weak generic advice like only saying diversify suppliers.
            Give stronger, specific alternatives when possible such as cheaper nearby sourcing belts, substitute states,
            backup procurement zones, mandi clusters, or lower-risk logistics corridors that make business sense.
            For farmer reports, also provide a Hindi version of the key explanation and action plan.
            Be conservative with risk scoring.
            If there is no strong current disruption signal from the location and weather context, return No Risk.
            If the live weather context looks calm or stable, do not force a risk label above Low unless there is a
            clearly stated active issue such as flooding, severe heat, heavy rain, crop disease, transport closure,
            storage failure, or a sharp market or policy disruption.
            Do not invent disease, logistics, or policy problems when the live context looks normal.
            Use Low only for mild pressure, Medium for meaningful but manageable pressure, High for serious disruption,
            and Very High only for severe active disruption.
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
                            Live Weather Context: {weatherContext}

                            Analyze the current agricultural supply chain risk for this crop and exact location.
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
                            - hindiDisruptionSummary
                            - hindiPrimaryThreat
                            - hindiDetailedProblem
                            - hindiMitigationStrategy
                            - hindiFarmerActions as a short list
                            - hindiGovernmentSchemes as a short list
                            - expectedSupplyImpactPercent as an integer
                            - expectedPriceIncreasePercent as an integer
                            - estimatedLossPercent as an integer
                            Use simple language.
                            Clearly describe the actual local problem in that place.
                            For enterpriseActions, mention stronger practical sourcing or route actions with example
                            locations or cheaper fallback areas whenever possible instead of vague advice.
                            Use No Risk if no meaningful current disruption is visible.
                            If the risk is High or Very High, call the sendEmergencyAlert tool using the same cropName, region,
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
                            .param("planningHorizonDays", request.getPlanningHorizonDays() == null ? "Not provided" : request.getPlanningHorizonDays())
                            .param("weatherContext", StringUtils.hasText(request.getWeatherContext()) ? request.getWeatherContext() : "Not provided"))
                    .call()
                    .entity(AiRiskAssessment.class);

            String normalizedRiskLevel = normalizeRiskLevel(aiRiskAssessment);
            String adjustedRiskLevel = adjustRiskLevelForLiveSignals(normalizedRiskLevel, request, aiRiskAssessment);

            RiskReport riskReport = new RiskReport();
            riskReport.setCropName(request.getCropName().trim());
            riskReport.setRegion(request.getRegion().trim());
            riskReport.setRiskLevel(adjustedRiskLevel);
            riskReport.setMitigationStrategy(normalizeMitigationStrategy(aiRiskAssessment));

            RiskReport savedRiskReport = riskReportRepository.save(riskReport);
            BigDecimal estimatedLossInr = estimateLossInr(request, aiRiskAssessment);

            return new RiskAnalysisResponse(
                    savedRiskReport.getId(),
                    savedRiskReport.getCropName(),
                    savedRiskReport.getRegion(),
                    adjustedRiskLevel,
                    savedRiskReport.getMitigationStrategy(),
                    normalizeStakeholderType(request),
                    defaultText(aiRiskAssessment.getDisruptionSummary(), "AgriPulse identified meaningful disruption pressure in this corridor."),
                    defaultText(aiRiskAssessment.getPrimaryThreat(), "Supply chain volatility"),
                    defaultText(aiRiskAssessment.getDetailedProblem(), "The model did not return a full disruption explanation."),
                    normalizeList(aiRiskAssessment.getRiskFactors()),
                    normalizeList(aiRiskAssessment.getEnterpriseActions()),
                    normalizeList(aiRiskAssessment.getFarmerActions()),
                    normalizeList(aiRiskAssessment.getGovernmentSchemes()),
                    defaultText(aiRiskAssessment.getHindiDisruptionSummary(), defaultText(aiRiskAssessment.getDisruptionSummary(), "Hindi summary not returned.")),
                    defaultText(aiRiskAssessment.getHindiPrimaryThreat(), defaultText(aiRiskAssessment.getPrimaryThreat(), "Hindi threat not returned.")),
                    defaultText(aiRiskAssessment.getHindiDetailedProblem(), defaultText(aiRiskAssessment.getDetailedProblem(), "Hindi problem explanation not returned.")),
                    defaultText(aiRiskAssessment.getHindiMitigationStrategy(), normalizeMitigationStrategy(aiRiskAssessment)),
                    normalizeList(aiRiskAssessment.getHindiFarmerActions()),
                    normalizeList(aiRiskAssessment.getHindiGovernmentSchemes()),
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
            case "no risk", "no-risk", "none", "minimal", "min", "minimum", "safe" -> "No Risk";
            case "low" -> "Low";
            case "medium", "med", "moderate" -> "Medium";
            case "high", "severe", "critical" -> "High";
            case "very high", "very-high", "extreme" -> "Very High";
            default -> throw new IllegalStateException("Unexpected risk level returned by the AI: " + aiRiskAssessment.getRiskLevel());
        };
    }

    private String normalizeMitigationStrategy(AiRiskAssessment aiRiskAssessment) {
        if (aiRiskAssessment == null || !StringUtils.hasText(aiRiskAssessment.getMitigationStrategy())) {
            return "No mitigation strategy was returned by the AI model.";
        }

        return aiRiskAssessment.getMitigationStrategy().trim();
    }

    private String adjustRiskLevelForLiveSignals(String normalizedRiskLevel, RiskAnalysisRequest request, AiRiskAssessment aiRiskAssessment) {
        if (!StringUtils.hasText(normalizedRiskLevel)) {
            return "Medium";
        }

        String weatherContext = request == null ? "" : defaultText(request.getWeatherContext(), "");
        String aiContext = String.join(" ",
                defaultText(aiRiskAssessment.getDisruptionSummary(), ""),
                defaultText(aiRiskAssessment.getPrimaryThreat(), ""),
                defaultText(aiRiskAssessment.getDetailedProblem(), ""),
                String.join(" ", normalizeList(aiRiskAssessment.getRiskFactors()))
        ).toLowerCase(Locale.ROOT);

        if (containsAny(aiContext,
                "no meaningful disruption",
                "no major disruption",
                "no current disruption",
                "stable conditions",
                "normal conditions",
                "operations remain stable")) {
            return "No Risk";
        }

        boolean stableWeather = isStableWeatherContext(weatherContext);
        boolean severeSignal = containsAny(aiContext,
                "flood", "flooding", "heat wave", "heatwave", "drought", "crop disease", "blight",
                "rust", "pest outbreak", "locust", "transport closure", "road closure", "storage failure",
                "export ban", "policy shock", "heavy rain", "extreme rainfall", "severe rainfall",
                "cyclone", "storm", "hailstorm", "landslide");

        if (!stableWeather || severeSignal) {
            return normalizedRiskLevel;
        }

        return switch (normalizedRiskLevel) {
            case "Very High" -> "Medium";
            case "High" -> "Low";
            case "Medium" -> "Low";
            case "Low" -> "No Risk";
            default -> normalizedRiskLevel;
        };
    }

    private boolean isStableWeatherContext(String weatherContext) {
        if (!StringUtils.hasText(weatherContext)) {
            return false;
        }

        String normalized = weatherContext.toLowerCase(Locale.ROOT);
        Integer weatherCode = extractIntAfter(normalized, "code ");
        Integer windSpeed = extractIntAfter(normalized, "wind ");
        Integer temperature = extractTemperatureCelsius(normalized);

        boolean calmWeatherCode = weatherCode != null && Arrays.asList(0, 1, 2, 3).contains(weatherCode);
        boolean calmWind = windSpeed != null && windSpeed <= 18;
        boolean normalTemperature = temperature != null && temperature >= 10 && temperature <= 36;

        return calmWeatherCode && calmWind && normalTemperature;
    }

    private Integer extractIntAfter(String text, String marker) {
        int index = text.indexOf(marker);
        if (index < 0) {
            return null;
        }

        StringBuilder digits = new StringBuilder();
        for (int i = index + marker.length(); i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isDigit(current)) {
                digits.append(current);
            }
            else if (digits.length() > 0) {
                break;
            }
        }

        if (digits.isEmpty()) {
            return null;
        }

        return Integer.parseInt(digits.toString());
    }

    private Integer extractTemperatureCelsius(String weatherContext) {
        for (String segment : weatherContext.split("\\|")) {
            String trimmed = segment.trim();
            if (trimmed.endsWith(" c")) {
                String numericPart = trimmed.substring(0, trimmed.length() - 2).trim();
                try {
                    return Integer.parseInt(numericPart);
                }
                catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private boolean containsAny(String text, String... needles) {
        if (!StringUtils.hasText(text)) {
            return false;
        }

        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
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
                            .filter(report -> "High".equalsIgnoreCase(report.getRiskLevel()) || "Very High".equalsIgnoreCase(report.getRiskLevel()))
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
