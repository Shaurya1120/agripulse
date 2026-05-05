package com.agripulse.app.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

// This service talks to the official mandi price dataset from data.gov.in.
// AgriService can use it as a trusted evidence source instead of relying only on AI guesses.
@Service
@Slf4j
public class MandiPriceService {

    private static final String DEFAULT_BASE_URL = "https://api.data.gov.in";
    // This resource id is the official "Current Daily Price of Various Commodities from Various Markets (Mandi)" dataset.
    private static final String DEFAULT_RESOURCE_ID = "9ef84268-d588-465a-a308-a864a43d0070";
    private static final DateTimeFormatter DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RestClient restClient;
    private final String apiKey;
    private final String resourceId;

    public MandiPriceService(
            RestClient.Builder restClientBuilder,
            @Value("${agripulse.market-data.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${agripulse.market-data.api-key:${DATA_GOV_IN_API_KEY:}}") String apiKey,
            @Value("${agripulse.market-data.resource-id:" + DEFAULT_RESOURCE_ID + "}") String resourceId) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.resourceId = resourceId;
    }

    public MarketEvidence findMarketEvidence(String cropName, String region, BigDecimal userRatePerKgInr, String stakeholderType) {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(cropName)) {
            return MarketEvidence.unavailable();
        }

        RegionParts regionParts = RegionParts.from(region);
        List<String> commodityAliases = commodityAliases(cropName);
        List<MandiRecord> bestRecords = List.of();
        String commodityUsed = null;

        for (String alias : commodityAliases) {
            bestRecords = queryWithBestScope(alias, regionParts);
            if (!bestRecords.isEmpty()) {
                commodityUsed = alias;
                break;
            }
        }

        if (bestRecords.isEmpty()) {
            return MarketEvidence.unavailable();
        }

        MandiRecord bestRecord = bestRecords.stream()
                .filter(record -> parseMoney(record.modalPrice()).compareTo(BigDecimal.ZERO) > 0)
                .max(Comparator
                        .comparing((MandiRecord record) -> locationScore(record, regionParts))
                        .thenComparing(record -> parseDate(record.arrivalDate()), Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(bestRecords.get(0));

        BigDecimal modalPricePerQuintal = parseMoney(bestRecord.modalPrice());
        if (modalPricePerQuintal.compareTo(BigDecimal.ZERO) <= 0) {
            return MarketEvidence.unavailable();
        }

        BigDecimal minPricePerQuintal = parseMoney(bestRecord.minPrice());
        BigDecimal maxPricePerQuintal = parseMoney(bestRecord.maxPrice());
        BigDecimal modalPricePerKg = modalPricePerQuintal.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal minPricePerKg = minPricePerQuintal.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal maxPricePerKg = maxPricePerQuintal.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        int spreadPercent = safePercent(maxPricePerQuintal.subtract(minPricePerQuintal), modalPricePerQuintal);
        int userGapPercent = userRatePerKgInr == null || userRatePerKgInr.compareTo(BigDecimal.ZERO) <= 0
                ? 0
                : safePercent(modalPricePerKg.subtract(userRatePerKgInr).abs(), userRatePerKgInr);

        boolean farmerView = "Farmer".equalsIgnoreCase(stakeholderType);
        boolean buyerView = !farmerView;

        int pricePressurePercent;
        if (userRatePerKgInr == null || userRatePerKgInr.compareTo(BigDecimal.ZERO) <= 0) {
            pricePressurePercent = Math.max(spreadPercent / 2, 0);
        } else if (buyerView) {
            pricePressurePercent = modalPricePerKg.compareTo(userRatePerKgInr) > 0
                    ? safePercent(modalPricePerKg.subtract(userRatePerKgInr), userRatePerKgInr)
                    : Math.max(spreadPercent / 2, 0);
        } else {
            pricePressurePercent = userRatePerKgInr.compareTo(modalPricePerKg) > 0
                    ? safePercent(userRatePerKgInr.subtract(modalPricePerKg), userRatePerKgInr)
                    : Math.max(spreadPercent / 2, 0);
        }

        String marketRiskLevel = mapMarketRiskLevel(pricePressurePercent, spreadPercent);
        int marketSupplyImpact = supplyImpactForMarketRisk(marketRiskLevel);
        int marketLossPercent = lossImpactForMarketRisk(marketRiskLevel, pricePressurePercent);

        String locationUsed = joinLocation(bestRecord.market(), bestRecord.district(), bestRecord.state());
        String summary = "Official mandi data from " + locationUsed
                + " shows " + commodityUsed
                + " near INR " + modalPricePerKg.stripTrailingZeros().toPlainString()
                + " per kg"
                + buildDateSuffix(bestRecord.arrivalDate()) + ".";

        List<String> factors = new ArrayList<>();
        factors.add("Verified mandi modal price is INR " + modalPricePerKg.stripTrailingZeros().toPlainString() + " per kg.");
        if (spreadPercent > 0) {
            factors.add("Daily mandi price spread is about " + spreadPercent + "% between min and max price.");
        }
        if (userGapPercent > 0 && userRatePerKgInr != null) {
            factors.add("This is about " + userGapPercent + "% away from the user-provided price assumption.");
        }
        if (StringUtils.hasText(bestRecord.variety())) {
            factors.add("Recorded variety: " + bestRecord.variety().trim() + ".");
        }

        return new MarketEvidence(
                true,
                commodityUsed,
                locationUsed,
                bestRecord.arrivalDate(),
                modalPricePerKg,
                minPricePerKg,
                maxPricePerKg,
                marketRiskLevel,
                pricePressurePercent,
                marketSupplyImpact,
                marketLossPercent,
                summary,
                factors.stream().limit(5).toList(),
                "data.gov.in AGMARKNET mandi price dataset"
        );
    }

    private List<MandiRecord> queryWithBestScope(String commodity, RegionParts regionParts) {
        List<MandiRecord> districtRecords = queryRecords(commodity, regionParts.state(), regionParts.district());
        if (!districtRecords.isEmpty()) {
            return districtRecords;
        }

        if (StringUtils.hasText(regionParts.state())) {
            List<MandiRecord> stateRecords = queryRecords(commodity, regionParts.state(), null);
            if (!stateRecords.isEmpty()) {
                return stateRecords;
            }
        }

        return queryRecords(commodity, null, null);
    }

    private List<MandiRecord> queryRecords(String commodity, String state, String district) {
        try {
            MandiApiResponse response = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/resource/{resourceId}")
                                .queryParam("api-key", apiKey)
                                .queryParam("format", "json")
                                .queryParam("limit", 15)
                                .queryParam("filters[commodity]", commodity);

                        if (StringUtils.hasText(state)) {
                            uriBuilder.queryParam("filters[state]", state);
                        }
                        if (StringUtils.hasText(district)) {
                            uriBuilder.queryParam("filters[district]", district);
                        }

                        return uriBuilder.build(resourceId);
                    })
                    .retrieve()
                    .body(MandiApiResponse.class);

            return response == null || response.records() == null ? List.of() : response.records();
        } catch (RuntimeException exception) {
            log.warn("Official mandi price lookup failed for commodity='{}', state='{}', district='{}': {}",
                    commodity, state, district, exception.getMessage());
            return List.of();
        }
    }

    private List<String> commodityAliases(String cropName) {
        String normalized = cropName.trim().toLowerCase(Locale.ROOT);
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(titleCaseCrop(cropName));

        if (normalized.contains("basmati")) {
            aliases.add("Basmati Rice");
        }
        if (normalized.contains("rice") || normalized.contains("paddy") || normalized.contains("dhan")) {
            aliases.add("Rice");
            aliases.add("Paddy(Dhan)(Common)");
            aliases.add("Paddy");
        }
        if (normalized.contains("wheat")) {
            aliases.add("Wheat");
        }
        if (normalized.contains("coffee")) {
            aliases.add("Coffee");
        }
        if (normalized.contains("turmeric")) {
            aliases.add("Turmeric");
        }
        if (normalized.contains("potato")) {
            aliases.add("Potato");
        }
        if (normalized.contains("onion")) {
            aliases.add("Onion");
        }
        if (normalized.contains("maize") || normalized.contains("corn")) {
            aliases.add("Maize");
        }

        return aliases.stream().filter(StringUtils::hasText).toList();
    }

    private String titleCaseCrop(String cropName) {
        return Arrays.stream(cropName.trim().split("\\s+"))
                .map(token -> token.substring(0, 1).toUpperCase(Locale.ROOT) + token.substring(1).toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private String joinLocation(String market, String district, String state) {
        return List.of(market, district, state).stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private int locationScore(MandiRecord record, RegionParts regionParts) {
        int score = 0;
        if (matchesPart(record.state(), regionParts.state())) {
            score += 2;
        }
        if (matchesPart(record.district(), regionParts.district())) {
            score += 3;
        }
        if (matchesPart(record.market(), regionParts.district())) {
            score += 1;
        }
        return score;
    }

    private boolean matchesPart(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private BigDecimal parseMoney(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return BigDecimal.ZERO;
        }
        String normalized = rawValue.replace(",", "").trim();
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    private LocalDate parseDate(String rawDate) {
        if (!StringUtils.hasText(rawDate)) {
            return null;
        }

        try {
            return LocalDate.parse(rawDate, DAY_MONTH_YEAR);
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDate.parse(rawDate);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String buildDateSuffix(String rawDate) {
        LocalDate date = parseDate(rawDate);
        return date == null ? "" : " on " + date;
    }

    private int safePercent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return numerator
                .multiply(BigDecimal.valueOf(100))
                .divide(denominator, 0, RoundingMode.HALF_UP)
                .abs()
                .intValue();
    }

    private String mapMarketRiskLevel(int pricePressurePercent, int spreadPercent) {
        int score = Math.max(pricePressurePercent, spreadPercent);
        if (score >= 25) return "Very High";
        if (score >= 16) return "High";
        if (score >= 8) return "Medium";
        if (score >= 3) return "Low";
        return "No Risk";
    }

    private int supplyImpactForMarketRisk(String riskLevel) {
        return switch (riskLevel) {
            case "No Risk" -> 0;
            case "Low" -> 3;
            case "Medium" -> 7;
            case "High" -> 12;
            case "Very High" -> 18;
            default -> 0;
        };
    }

    private int lossImpactForMarketRisk(String riskLevel, int pricePressurePercent) {
        return switch (riskLevel) {
            case "No Risk" -> 0;
            case "Low" -> Math.max(2, pricePressurePercent / 2);
            case "Medium" -> Math.max(6, pricePressurePercent / 2);
            case "High" -> Math.max(12, pricePressurePercent / 2);
            case "Very High" -> Math.max(18, pricePressurePercent / 2);
            default -> 0;
        };
    }

    private record RegionParts(String district, String state) {
        private static RegionParts from(String region) {
            if (!StringUtils.hasText(region)) {
                return new RegionParts(null, null);
            }

            String[] parts = region.split(",");
            if (parts.length >= 2) {
                return new RegionParts(parts[0].trim(), parts[parts.length - 1].trim());
            }

            return new RegionParts(region.trim(), region.trim());
        }
    }

    public record MarketEvidence(
            boolean available,
            String commodity,
            String locationUsed,
            String arrivalDate,
            BigDecimal modalPricePerKgInr,
            BigDecimal minPricePerKgInr,
            BigDecimal maxPricePerKgInr,
            String riskLevel,
            Integer pricePressurePercent,
            Integer expectedSupplyImpactPercent,
            Integer estimatedLossPercent,
            String summary,
            List<String> factors,
            String sourceLabel
    ) {
        static MarketEvidence unavailable() {
            return new MarketEvidence(
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "No Risk",
                    0,
                    0,
                    0,
                    "No official mandi price signal was found for this crop and location.",
                    List.of("Official mandi price data not available for this exact query."),
                    null
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MandiApiResponse(List<MandiRecord> records) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MandiRecord(
            String state,
            String district,
            String market,
            String commodity,
            String variety,
            @JsonProperty("arrival_date") String arrivalDate,
            @JsonProperty("min_price") String minPrice,
            @JsonProperty("max_price") String maxPrice,
            @JsonProperty("modal_price") String modalPrice
    ) {
    }
}
