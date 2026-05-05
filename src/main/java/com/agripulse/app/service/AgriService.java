package com.agripulse.app.service;

import com.agripulse.app.dto.AiRiskAssessment;
import com.agripulse.app.dto.HistoryPageResponse;
import com.agripulse.app.dto.RiskAnalysisRequest;
import com.agripulse.app.dto.RiskAnalysisResponse;
import com.agripulse.app.dto.RiskMapPoint;
import com.agripulse.app.dto.UiDashboardData;
import com.agripulse.app.model.RiskReport;
import com.agripulse.app.model.UserAccount;
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
            Keep the risk level limited to one of these values only: No Risk, Low, Medium, High, Very High.
            Include practical detail about real disruption causes such as crop disease, heat wave, excess rainfall,
            flood risk, transport bottlenecks, market price swings, storage stress, and policy/export shocks when relevant.
            Tailor the answer to either an enterprise buyer or a farmer-producer based on stakeholderType.
            Support nearly any crop and nearly any location.
            Treat the provided location as exact user input. If the user gives a district, city, block, or state such as
            Malda, West Bengal, use that exact place in your reasoning instead of replacing it with a generic region.
            Use simple language that a new visitor can understand quickly.
            Treat the verified live weather evidence as factual.
            Do not contradict the verified weather evidence.
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
    private final EmergencyAlertTool emergencyAlertTool;
    private final MandiPriceService mandiPriceService;
    private final UserAccountService userAccountService;
    private final WeatherLookupService weatherLookupService;

    public RiskAnalysisResponse analyzeRisk(RiskAnalysisRequest request, String userEmail) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }

        try {
            UserAccount userAccount = userAccountService.getRequiredUser(userEmail);
            String resolvedWeatherContext = weatherLookupService.resolveWeatherContext(request.getRegion(), request.getWeatherContext());
            WeatherEvidence weatherEvidence = buildWeatherEvidence(request, resolvedWeatherContext);
            String stakeholderType = normalizeStakeholderType(request);
            MandiPriceService.MarketEvidence marketEvidence = mandiPriceService.findMarketEvidence(
                    request.getCropName(),
                    request.getRegion(),
                    request.getCropRatePerKgInr(),
                    stakeholderType
            );

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
                            Verified Weather Risk Level: {verifiedRiskLevel}
                            Verified Primary Threat: {verifiedPrimaryThreat}
                            Verified Weather Summary: {verifiedSummary}
                            Verified Weather Factors: {verifiedFactors}
                            Verified Market Risk Level: {verifiedMarketRiskLevel}
                            Verified Market Summary: {verifiedMarketSummary}
                            Verified Market Factors: {verifiedMarketFactors}
                            Verified Market Modal Price INR per Kg: {verifiedMarketPrice}
                            Verified Market Source: {verifiedMarketSource}

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
                            Keep your answer aligned with the verified weather evidence above.
                            Keep your answer aligned with the verified official mandi price evidence above.
                            If the verified risk level is No Risk or Low, do not describe a severe disruption.
                            If verified mandi data looks stable and the weather is stable, do not force a strong price shock.
                            Return only the structured risk result for this request.
                            """)
                            .param("cropName", request.getCropName())
                            .param("region", request.getRegion())
                            .param("stakeholderType", stakeholderType)
                            .param("quantityTonnes", safeNumber(request.getQuantityTonnes()))
                            .param("cropRatePerKgInr", safeNumber(request.getCropRatePerKgInr()))
                            .param("farmAreaAcres", safeNumber(request.getFarmAreaAcres()))
                            .param("planningHorizonDays", request.getPlanningHorizonDays() == null ? "Not provided" : request.getPlanningHorizonDays())
                            .param("weatherContext", StringUtils.hasText(resolvedWeatherContext) ? resolvedWeatherContext : "Not provided")
                            .param("verifiedRiskLevel", weatherEvidence.riskLevel())
                            .param("verifiedPrimaryThreat", weatherEvidence.primaryThreat())
                            .param("verifiedSummary", weatherEvidence.disruptionSummary())
                            .param("verifiedFactors", String.join(", ", weatherEvidence.riskFactors()))
                            .param("verifiedMarketRiskLevel", marketEvidence.riskLevel())
                            .param("verifiedMarketSummary", marketEvidence.summary())
                            .param("verifiedMarketFactors", String.join(", ", marketEvidence.factors()))
                            .param("verifiedMarketPrice", marketEvidence.modalPricePerKgInr() == null ? "Not available" : marketEvidence.modalPricePerKgInr().stripTrailingZeros().toPlainString())
                            .param("verifiedMarketSource", defaultText(marketEvidence.sourceLabel(), "Not available")))
                    .call()
                    .entity(AiRiskAssessment.class);

            String normalizedRiskLevel = normalizeRiskLevel(aiRiskAssessment);
            String adjustedRiskLevel = determineFinalRiskLevel(normalizedRiskLevel, resolvedWeatherContext, aiRiskAssessment, weatherEvidence, marketEvidence);

            RiskReport riskReport = new RiskReport();
            riskReport.setCropName(request.getCropName().trim());
            riskReport.setRegion(request.getRegion().trim());
            riskReport.setRiskLevel(adjustedRiskLevel);
            riskReport.setMitigationStrategy(normalizeMitigationStrategy(aiRiskAssessment));
            riskReport.setUserAccount(userAccount);

            RiskReport savedRiskReport = riskReportRepository.save(riskReport);

            if ("High".equalsIgnoreCase(adjustedRiskLevel) || "Very High".equalsIgnoreCase(adjustedRiskLevel)) {
                emergencyAlertTool.sendEmergencyAlert(
                        request.getCropName().trim(),
                        request.getRegion().trim(),
                        adjustedRiskLevel,
                        riskReport.getMitigationStrategy()
                );
            }

            return new RiskAnalysisResponse(
                    savedRiskReport.getId(),
                    savedRiskReport.getCropName(),
                    savedRiskReport.getRegion(),
                    adjustedRiskLevel,
                    savedRiskReport.getMitigationStrategy(),
                    stakeholderType,
                    chooseDisruptionSummary(aiRiskAssessment, weatherEvidence, marketEvidence, adjustedRiskLevel),
                    choosePrimaryThreat(aiRiskAssessment, weatherEvidence, marketEvidence, adjustedRiskLevel),
                    chooseDetailedProblem(aiRiskAssessment, weatherEvidence, marketEvidence, adjustedRiskLevel),
                    chooseRiskFactors(aiRiskAssessment, weatherEvidence, marketEvidence, adjustedRiskLevel),
                    normalizeList(aiRiskAssessment.getEnterpriseActions()),
                    normalizeList(aiRiskAssessment.getFarmerActions()),
                    normalizeList(aiRiskAssessment.getGovernmentSchemes()),
                    chooseHindiSummary(aiRiskAssessment, weatherEvidence, marketEvidence, adjustedRiskLevel),
                    chooseHindiPrimaryThreat(aiRiskAssessment, weatherEvidence, marketEvidence, adjustedRiskLevel),
                    chooseHindiDetailedProblem(aiRiskAssessment, weatherEvidence, marketEvidence, adjustedRiskLevel),
                    defaultText(aiRiskAssessment.getHindiMitigationStrategy(), normalizeMitigationStrategy(aiRiskAssessment)),
                    normalizeList(aiRiskAssessment.getHindiFarmerActions()),
                    normalizeList(aiRiskAssessment.getHindiGovernmentSchemes()),
                    chooseExpectedSupplyImpact(aiRiskAssessment, weatherEvidence, marketEvidence),
                    chooseExpectedPriceIncrease(aiRiskAssessment, weatherEvidence, marketEvidence),
                    chooseEstimatedLossPercent(aiRiskAssessment, weatherEvidence, marketEvidence),
                    request.getQuantityTonnes(),
                    request.getCropRatePerKgInr(),
                    request.getFarmAreaAcres(),
                    request.getPlanningHorizonDays(),
                    recalculateLossFromEvidence(request, aiRiskAssessment, weatherEvidence, marketEvidence),
                    resolvedWeatherContext
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

    private WeatherEvidence buildWeatherEvidence(RiskAnalysisRequest request, String weatherContext) {
        if (!StringUtils.hasText(weatherContext) || weatherContext.toLowerCase(Locale.ROOT).contains("weather unavailable")) {
            return new WeatherEvidence(
                    false,
                    "Low",
                    "Location weather could not be verified",
                    "AgriPulse could not verify live weather for the typed location yet, so this report should be treated as a lower-confidence operational snapshot.",
                    "Live weather data could not be verified for this place. Use a district, city, or state format such as 'Muzaffarnagar, Uttar Pradesh' for a stronger confirmed report.",
                    List.of("Live weather verification unavailable"),
                    5,
                    3,
                    4
            );
        }

        String normalized = weatherContext.toLowerCase(Locale.ROOT);
        Integer weatherCode = extractIntAfter(normalized, "code ");
        Integer windSpeed = extractIntAfter(normalized, "wind ");
        Integer temperature = extractTemperatureCelsius(normalized);

        int weatherScore = scoreWeatherCode(weatherCode);
        int windScore = scoreWind(windSpeed);
        int temperatureScore = scoreTemperature(temperature);
        int totalScore = Math.min(weatherScore + windScore + temperatureScore, 100);

        String riskLevel = mapScoreToRisk(totalScore);
        String primaryThreat = determinePrimaryThreat(weatherCode, windSpeed, temperature, weatherScore, windScore, temperatureScore);
        List<String> factors = buildWeatherFactors(weatherCode, windSpeed, temperature, totalScore);
        String summary = buildEvidenceSummary(riskLevel, primaryThreat, request == null ? "" : request.getRegion());
        String detailedProblem = buildDetailedEvidenceProblem(weatherCode, windSpeed, temperature, riskLevel, primaryThreat);

        return new WeatherEvidence(
                true,
                riskLevel,
                primaryThreat,
                summary,
                detailedProblem,
                factors,
                supplyImpactForRisk(riskLevel),
                priceImpactForRisk(riskLevel),
                lossImpactForRisk(riskLevel)
        );
    }

    private String adjustRiskLevelForLiveSignals(String normalizedRiskLevel, String weatherContext, AiRiskAssessment aiRiskAssessment) {
        if (!StringUtils.hasText(normalizedRiskLevel)) {
            return "Medium";
        }

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

    private String determineFinalRiskLevel(
            String normalizedRiskLevel,
            String weatherContext,
            AiRiskAssessment aiRiskAssessment,
            WeatherEvidence weatherEvidence,
            MandiPriceService.MarketEvidence marketEvidence) {
        String weatherAdjusted = weatherEvidence.liveEvidenceAvailable()
                ? weatherEvidence.riskLevel()
                : adjustRiskLevelForLiveSignals(normalizedRiskLevel, weatherContext, aiRiskAssessment);

        if (!marketEvidence.available()) {
            return weatherAdjusted;
        }

        int finalRank = Math.max(riskRank(weatherAdjusted), riskRank(marketEvidence.riskLevel()));
        return riskLevelFromRank(finalRank);
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

    private int riskRank(String riskLevel) {
        return switch (defaultText(riskLevel, "No Risk")) {
            case "Very High" -> 5;
            case "High" -> 4;
            case "Medium" -> 3;
            case "Low" -> 2;
            default -> 1;
        };
    }

    private String riskLevelFromRank(int rank) {
        return switch (rank) {
            case 5 -> "Very High";
            case 4 -> "High";
            case 3 -> "Medium";
            case 2 -> "Low";
            default -> "No Risk";
        };
    }

    private String verifiedSummary(WeatherEvidence weatherEvidence, MandiPriceService.MarketEvidence marketEvidence, String finalRiskLevel) {
        if (marketEvidence.available() && riskRank(marketEvidence.riskLevel()) >= riskRank(weatherEvidence.riskLevel())) {
            if ("No Risk".equals(finalRiskLevel)) {
                return "No strong live weather or official mandi price disruption is visible right now.";
            }
            return marketEvidence.summary();
        }
        return weatherEvidence.disruptionSummary();
    }

    private String verifiedPrimaryThreat(WeatherEvidence weatherEvidence, MandiPriceService.MarketEvidence marketEvidence) {
        if (marketEvidence.available() && riskRank(marketEvidence.riskLevel()) > riskRank(weatherEvidence.riskLevel())) {
            return "Official mandi price pressure";
        }
        return weatherEvidence.primaryThreat();
    }

    private String verifiedDetailedProblem(WeatherEvidence weatherEvidence, MandiPriceService.MarketEvidence marketEvidence, String finalRiskLevel) {
        if ("No Risk".equals(finalRiskLevel) && marketEvidence.available() && "No Risk".equals(marketEvidence.riskLevel())) {
            return "Live weather looks stable and official mandi price data does not show a strong current disruption signal for this crop and location.";
        }

        if (marketEvidence.available() && riskRank(marketEvidence.riskLevel()) > riskRank(weatherEvidence.riskLevel())) {
            return marketEvidence.summary() + " This verified market signal is stronger than the live weather signal, so price movement is the main current risk factor.";
        }

        if (marketEvidence.available() && riskRank(marketEvidence.riskLevel()) >= 3 && riskRank(weatherEvidence.riskLevel()) >= 3) {
            return weatherEvidence.detailedProblem() + " Official mandi price data also confirms active market pressure in " + defaultText(marketEvidence.locationUsed(), "the matched mandi market") + ".";
        }

        return weatherEvidence.detailedProblem();
    }

    private List<String> verifiedFactors(
            WeatherEvidence weatherEvidence,
            MandiPriceService.MarketEvidence marketEvidence,
            String finalRiskLevel) {
        List<String> merged = java.util.stream.Stream.concat(
                        weatherEvidence.riskFactors().stream(),
                        marketEvidence.available() ? marketEvidence.factors().stream() : java.util.stream.Stream.empty())
                .distinct()
                .limit("No Risk".equals(finalRiskLevel) ? 4 : 6)
                .toList();
        return merged.isEmpty() ? List.of("No verified disruption factor was found.") : merged;
    }

    private String verifiedHindiSummary(WeatherEvidence weatherEvidence, MandiPriceService.MarketEvidence marketEvidence, String finalRiskLevel) {
        if (marketEvidence.available() && riskRank(marketEvidence.riskLevel()) > riskRank(weatherEvidence.riskLevel())) {
            if ("No Risk".equals(finalRiskLevel)) {
                return "लाइव मौसम और आधिकारिक मंडी कीमतों के आधार पर अभी कोई बड़ा जोखिम संकेत नहीं दिख रहा है।";
            }
            return "आधिकारिक मंडी कीमत के अनुसार इस फसल पर अभी बाजार दबाव दिख रहा है।";
        }
        return hindiSummaryForEvidence(weatherEvidence);
    }

    private String verifiedHindiPrimaryThreat(WeatherEvidence weatherEvidence, MandiPriceService.MarketEvidence marketEvidence) {
        if (marketEvidence.available() && riskRank(marketEvidence.riskLevel()) > riskRank(weatherEvidence.riskLevel())) {
            return "आधिकारिक मंडी कीमत का दबाव";
        }
        return hindiPrimaryThreatForEvidence(weatherEvidence);
    }

    private String verifiedHindiDetailedProblem(WeatherEvidence weatherEvidence, MandiPriceService.MarketEvidence marketEvidence, String finalRiskLevel) {
        if ("No Risk".equals(finalRiskLevel) && marketEvidence.available() && "No Risk".equals(marketEvidence.riskLevel())) {
            return "लाइव मौसम सामान्य दिख रहा है और आधिकारिक मंडी कीमतों में भी अभी कोई बड़ा जोखिम संकेत नहीं दिख रहा है।";
        }
        if (marketEvidence.available() && riskRank(marketEvidence.riskLevel()) > riskRank(weatherEvidence.riskLevel())) {
            return "आधिकारिक मंडी कीमत के आधार पर इस फसल की बाजार स्थिति में दबाव दिख रहा है, इसलिए यह रिपोर्ट कीमत से जुड़े जोखिम को मुख्य समस्या मानती है।";
        }
        if (marketEvidence.available() && riskRank(marketEvidence.riskLevel()) >= 3 && riskRank(weatherEvidence.riskLevel()) >= 3) {
            return hindiDetailedProblemForEvidence(weatherEvidence) + " साथ ही आधिकारिक मंडी कीमतें भी बाजार दबाव की पुष्टि कर रही हैं।";
        }
        return hindiDetailedProblemForEvidence(weatherEvidence);
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

    private Integer chooseExpectedSupplyImpact(
            AiRiskAssessment aiRiskAssessment,
            WeatherEvidence weatherEvidence,
            MandiPriceService.MarketEvidence marketEvidence) {
        int verified = 0;
        if (weatherEvidence.liveEvidenceAvailable()) {
            verified = Math.max(verified, weatherEvidence.expectedSupplyImpactPercent());
        }
        if (marketEvidence.available()) {
            verified = Math.max(verified, marketEvidence.expectedSupplyImpactPercent());
        }
        Integer fallback = normalizePercent(aiRiskAssessment.getExpectedSupplyImpactPercent());
        return verified > 0 ? verified : (fallback == null ? 0 : fallback);
    }

    private Integer chooseExpectedPriceIncrease(
            AiRiskAssessment aiRiskAssessment,
            WeatherEvidence weatherEvidence,
            MandiPriceService.MarketEvidence marketEvidence) {
        int verified = 0;
        if (weatherEvidence.liveEvidenceAvailable()) {
            verified = Math.max(verified, weatherEvidence.expectedPriceIncreasePercent());
        }
        if (marketEvidence.available()) {
            verified = Math.max(verified, marketEvidence.pricePressurePercent());
        }
        Integer fallback = normalizePercent(aiRiskAssessment.getExpectedPriceIncreasePercent());
        return verified > 0 ? verified : (fallback == null ? 0 : fallback);
    }

    private Integer chooseEstimatedLossPercent(
            AiRiskAssessment aiRiskAssessment,
            WeatherEvidence weatherEvidence,
            MandiPriceService.MarketEvidence marketEvidence) {
        int verified = 0;
        if (weatherEvidence.liveEvidenceAvailable()) {
            verified = Math.max(verified, weatherEvidence.estimatedLossPercent());
        }
        if (marketEvidence.available()) {
            verified = Math.max(verified, marketEvidence.estimatedLossPercent());
        }
        Integer fallback = normalizePercent(aiRiskAssessment.getEstimatedLossPercent());
        return verified > 0 ? verified : (fallback == null ? 0 : fallback);
    }

    private String chooseDisruptionSummary(
            AiRiskAssessment aiRiskAssessment,
            WeatherEvidence weatherEvidence,
            MandiPriceService.MarketEvidence marketEvidence,
            String finalRiskLevel) {
        if ("No Risk".equals(finalRiskLevel) || "Low".equals(finalRiskLevel)) {
            return verifiedSummary(weatherEvidence, marketEvidence, finalRiskLevel);
        }
        return defaultText(aiRiskAssessment.getDisruptionSummary(), verifiedSummary(weatherEvidence, marketEvidence, finalRiskLevel));
    }

    private String choosePrimaryThreat(
            AiRiskAssessment aiRiskAssessment,
            WeatherEvidence weatherEvidence,
            MandiPriceService.MarketEvidence marketEvidence,
            String finalRiskLevel) {
        if ("No Risk".equals(finalRiskLevel) || "Low".equals(finalRiskLevel)) {
            return verifiedPrimaryThreat(weatherEvidence, marketEvidence);
        }
        return defaultText(aiRiskAssessment.getPrimaryThreat(), verifiedPrimaryThreat(weatherEvidence, marketEvidence));
    }

    private String chooseDetailedProblem(
            AiRiskAssessment aiRiskAssessment,
            WeatherEvidence weatherEvidence,
            MandiPriceService.MarketEvidence marketEvidence,
            String finalRiskLevel) {
        if ("No Risk".equals(finalRiskLevel) || "Low".equals(finalRiskLevel)) {
            return verifiedDetailedProblem(weatherEvidence, marketEvidence, finalRiskLevel);
        }
        return defaultText(aiRiskAssessment.getDetailedProblem(), verifiedDetailedProblem(weatherEvidence, marketEvidence, finalRiskLevel));
    }

    private List<String> chooseRiskFactors(
            AiRiskAssessment aiRiskAssessment,
            WeatherEvidence weatherEvidence,
            MandiPriceService.MarketEvidence marketEvidence,
            String finalRiskLevel) {
        List<String> verifiedFactors = verifiedFactors(weatherEvidence, marketEvidence, finalRiskLevel);
        List<String> aiFactors = normalizeList(aiRiskAssessment.getRiskFactors());
        if (aiFactors.isEmpty() || "No Risk".equals(finalRiskLevel) || "Low".equals(finalRiskLevel)) {
            return verifiedFactors;
        }

        return java.util.stream.Stream.concat(verifiedFactors.stream(), aiFactors.stream())
                .distinct()
                .limit(6)
                .toList();
    }

    private String chooseHindiSummary(
            AiRiskAssessment aiRiskAssessment,
            WeatherEvidence weatherEvidence,
            MandiPriceService.MarketEvidence marketEvidence,
            String finalRiskLevel) {
        if ("No Risk".equals(finalRiskLevel) || "Low".equals(finalRiskLevel)) {
            return verifiedHindiSummary(weatherEvidence, marketEvidence, finalRiskLevel);
        }
        return defaultText(aiRiskAssessment.getHindiDisruptionSummary(), verifiedHindiSummary(weatherEvidence, marketEvidence, finalRiskLevel));
    }

    private String chooseHindiPrimaryThreat(
            AiRiskAssessment aiRiskAssessment,
            WeatherEvidence weatherEvidence,
            MandiPriceService.MarketEvidence marketEvidence,
            String finalRiskLevel) {
        if ("No Risk".equals(finalRiskLevel) || "Low".equals(finalRiskLevel)) {
            return verifiedHindiPrimaryThreat(weatherEvidence, marketEvidence);
        }
        return defaultText(aiRiskAssessment.getHindiPrimaryThreat(), verifiedHindiPrimaryThreat(weatherEvidence, marketEvidence));
    }

    private String chooseHindiDetailedProblem(
            AiRiskAssessment aiRiskAssessment,
            WeatherEvidence weatherEvidence,
            MandiPriceService.MarketEvidence marketEvidence,
            String finalRiskLevel) {
        if ("No Risk".equals(finalRiskLevel) || "Low".equals(finalRiskLevel)) {
            return verifiedHindiDetailedProblem(weatherEvidence, marketEvidence, finalRiskLevel);
        }
        return defaultText(aiRiskAssessment.getHindiDetailedProblem(), verifiedHindiDetailedProblem(weatherEvidence, marketEvidence, finalRiskLevel));
    }

    private BigDecimal recalculateLossFromEvidence(
            RiskAnalysisRequest request,
            AiRiskAssessment aiRiskAssessment,
            WeatherEvidence weatherEvidence,
            MandiPriceService.MarketEvidence marketEvidence) {
        Integer lossPercent = chooseEstimatedLossPercent(aiRiskAssessment, weatherEvidence, marketEvidence);
        BigDecimal referenceRate = request == null ? null : request.getCropRatePerKgInr();

        if (marketEvidence.available() && marketEvidence.modalPricePerKgInr() != null) {
            referenceRate = marketEvidence.modalPricePerKgInr();
        }

        if (request == null || request.getQuantityTonnes() == null || referenceRate == null || lossPercent == null) {
            return null;
        }

        return request.getQuantityTonnes()
                .multiply(BigDecimal.valueOf(1000))
                .multiply(referenceRate)
                .multiply(BigDecimal.valueOf(lossPercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private int scoreWeatherCode(Integer weatherCode) {
        if (weatherCode == null) {
            return 10;
        }
        return switch (weatherCode) {
            case 0, 1, 2, 3 -> 0;
            case 45, 48 -> 5;
            case 51, 53, 55 -> 10;
            case 56, 57 -> 20;
            case 61, 63, 71, 73, 80 -> 20;
            case 65, 75, 77, 81 -> 35;
            case 66, 67, 82, 85, 86 -> 45;
            case 95 -> 55;
            case 96, 99 -> 70;
            default -> 15;
        };
    }

    private int scoreWind(Integer windSpeed) {
        if (windSpeed == null) {
            return 0;
        }
        if (windSpeed > 50) return 25;
        if (windSpeed > 35) return 15;
        if (windSpeed > 25) return 8;
        return 0;
    }

    private int scoreTemperature(Integer temperature) {
        if (temperature == null) {
            return 0;
        }
        if (temperature >= 42) return 35;
        if (temperature >= 38) return 25;
        if (temperature >= 35) return 12;
        if (temperature <= 3) return 25;
        if (temperature <= 7) return 12;
        return 0;
    }

    private String mapScoreToRisk(int score) {
        if (score >= 70) return "Very High";
        if (score >= 45) return "High";
        if (score >= 25) return "Medium";
        if (score >= 10) return "Low";
        return "No Risk";
    }

    private String determinePrimaryThreat(Integer weatherCode, Integer windSpeed, Integer temperature, int weatherScore, int windScore, int temperatureScore) {
        if (temperatureScore > 0 && temperatureScore >= weatherScore && temperatureScore >= windScore && temperature != null) {
            return temperature >= 35 ? "Heat stress" : "Cold stress";
        }
        if (weatherScore > 0 && weatherScore >= windScore && weatherCode != null) {
            if (Arrays.asList(65, 66, 67, 80, 81, 82, 95, 96, 99).contains(weatherCode)) {
                return "Heavy rain or storm activity";
            }
            if (Arrays.asList(51, 53, 55, 61, 63).contains(weatherCode)) {
                return "Rain pressure";
            }
        }
        if (windScore > 0 && windSpeed != null) {
            return "Strong wind exposure";
        }
        return "Stable weather conditions";
    }

    private List<String> buildWeatherFactors(Integer weatherCode, Integer windSpeed, Integer temperature, int totalScore) {
        java.util.ArrayList<String> factors = new java.util.ArrayList<>();
        if (temperature != null) {
            if (temperature >= 38) {
                factors.add("Very high temperature is stressing the crop environment.");
            }
            else if (temperature >= 35) {
                factors.add("High temperature can increase crop stress and irrigation demand.");
            }
            else if (temperature <= 7) {
                factors.add("Low temperature can slow crop activity and affect quality.");
            }
        }
        if (windSpeed != null && windSpeed > 25) {
            factors.add("Wind speed is high enough to affect field operations and handling.");
        }
        if (weatherCode != null) {
            if (Arrays.asList(65, 66, 67, 82, 95, 96, 99).contains(weatherCode)) {
                factors.add("The live weather code points to heavy rain, storm, or severe precipitation.");
            }
            else if (Arrays.asList(51, 53, 55, 61, 63, 80, 81).contains(weatherCode)) {
                factors.add("The live weather code shows active rain or showers.");
            }
        }
        if (factors.isEmpty()) {
            if (totalScore < 10) {
                factors.add("No strong live weather disruption is visible right now.");
            } else {
                factors.add("Only mild live weather pressure is visible right now.");
            }
        }
        return factors.stream().limit(5).toList();
    }

    private String buildEvidenceSummary(String riskLevel, String primaryThreat, String region) {
        if ("No Risk".equals(riskLevel)) {
            return "No strong live weather disruption is visible right now for " + defaultText(region, "this location") + ".";
        }
        if ("Low".equals(riskLevel)) {
            return "Only mild live weather pressure is visible right now for " + defaultText(region, "this location") + ".";
        }
        return primaryThreat + " is creating live operational pressure in " + defaultText(region, "this location") + ".";
    }

    private String buildDetailedEvidenceProblem(Integer weatherCode, Integer windSpeed, Integer temperature, String riskLevel, String primaryThreat) {
        if ("No Risk".equals(riskLevel)) {
            return "The live weather snapshot looks stable enough that no clear current disruption signal is visible from weather alone.";
        }

        StringBuilder builder = new StringBuilder(primaryThreat)
                .append(" is the strongest verified live weather signal.");
        if (temperature != null) {
            builder.append(" Temperature is ").append(temperature).append(" C.");
        }
        if (windSpeed != null) {
            builder.append(" Wind is ").append(windSpeed).append(" km/h.");
        }
        if (weatherCode != null) {
            builder.append(" Weather code ").append(weatherCode).append(" adds current field-condition evidence.");
        }
        builder.append(" This report is grounded first in the live weather snapshot, so the operational explanation should be read as a weather-linked risk signal.");
        return builder.toString();
    }

    private Integer supplyImpactForRisk(String riskLevel) {
        return switch (riskLevel) {
            case "No Risk" -> 1;
            case "Low" -> 5;
            case "Medium" -> 12;
            case "High" -> 24;
            case "Very High" -> 40;
            default -> 8;
        };
    }

    private Integer priceImpactForRisk(String riskLevel) {
        return switch (riskLevel) {
            case "No Risk" -> 0;
            case "Low" -> 3;
            case "Medium" -> 8;
            case "High" -> 16;
            case "Very High" -> 28;
            default -> 6;
        };
    }

    private Integer lossImpactForRisk(String riskLevel) {
        return switch (riskLevel) {
            case "No Risk" -> 0;
            case "Low" -> 4;
            case "Medium" -> 10;
            case "High" -> 20;
            case "Very High" -> 35;
            default -> 8;
        };
    }

    private String hindiSummaryForEvidence(WeatherEvidence weatherEvidence) {
        if ("No Risk".equals(weatherEvidence.riskLevel())) {
            return "लाइव मौसम के आधार पर अभी कोई बड़ा जोखिम संकेत नहीं दिख रहा है।";
        }
        if ("Low".equals(weatherEvidence.riskLevel())) {
            return "लाइव मौसम के आधार पर अभी हल्का दबाव दिख रहा है।";
        }
        return "लाइव मौसम के आधार पर अभी " + weatherEvidence.primaryThreat() + " का असर दिख रहा है।";
    }

    private String hindiPrimaryThreatForEvidence(WeatherEvidence weatherEvidence) {
        return switch (weatherEvidence.primaryThreat()) {
            case "Heat stress" -> "गर्मी का दबाव";
            case "Cold stress" -> "ठंड का दबाव";
            case "Heavy rain or storm activity" -> "भारी बारिश या तूफानी गतिविधि";
            case "Rain pressure" -> "बारिश का दबाव";
            case "Strong wind exposure" -> "तेज हवा का असर";
            default -> "मौसम की स्थिति सामान्य है";
        };
    }

    private String hindiDetailedProblemForEvidence(WeatherEvidence weatherEvidence) {
        if ("No Risk".equals(weatherEvidence.riskLevel())) {
            return "लाइव मौसम संकेतों के आधार पर अभी कोई स्पष्ट बड़ी बाधा नहीं दिख रही है।";
        }
        return "यह रिपोर्ट पहले लाइव मौसम संकेतों पर आधारित है। " + hindiPrimaryThreatForEvidence(weatherEvidence) + " इस समय सबसे बड़ा सत्यापित संकेत है।";
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

    public HistoryPageResponse getHistoryPage(String userEmail, int page, int size) {
        UserAccount userAccount = userAccountService.getRequiredUser(userEmail);
        int sanitizedPage = Math.max(page, 0);
        int sanitizedSize = Math.min(Math.max(size, 1), 20);

        Page<RiskReport> reports = riskReportRepository.findAllByUserAccountOrderByCreatedAtDesc(userAccount, PageRequest.of(sanitizedPage, sanitizedSize));

        return new HistoryPageResponse(
                reports.getContent().stream().map(RiskAnalysisResponse::fromEntity).toList(),
                reports.getNumber(),
                reports.getSize(),
                reports.getTotalElements(),
                reports.getTotalPages()
        );
    }

    public UiDashboardData getDashboardData(String userEmail) {
        UserAccount userAccount = userAccountService.getRequiredUser(userEmail);
        Page<RiskReport> recentReportsPage = riskReportRepository.findAllByUserAccountOrderByCreatedAtDesc(userAccount, PageRequest.of(0, 50));
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

    private record WeatherEvidence(
            boolean liveEvidenceAvailable,
            String riskLevel,
            String primaryThreat,
            String disruptionSummary,
            String detailedProblem,
            List<String> riskFactors,
            Integer expectedSupplyImpactPercent,
            Integer expectedPriceIncreasePercent,
            Integer estimatedLossPercent
    ) {
    }
}
