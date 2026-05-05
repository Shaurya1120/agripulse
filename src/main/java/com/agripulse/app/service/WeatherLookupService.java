package com.agripulse.app.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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

// This service resolves approximate user locations into a verified Open-Meteo weather snapshot.
// It helps AgriPulse stay useful even when the user types a looser place name like
// "muzaffarnagar uttar pradesh" instead of a perfectly formatted address.
@Service
@Slf4j
public class WeatherLookupService {

    private static final List<String> INDIAN_REGIONS = List.of(
            "andaman and nicobar islands", "arunachal pradesh", "andhra pradesh", "assam",
            "bihar", "chandigarh", "chhattisgarh", "dadra and nagar haveli and daman and diu",
            "delhi", "goa", "gujarat", "haryana", "himachal pradesh", "jammu and kashmir",
            "jharkhand", "karnataka", "kerala", "ladakh", "lakshadweep", "madhya pradesh",
            "maharashtra", "manipur", "meghalaya", "mizoram", "nagaland", "odisha",
            "puducherry", "punjab", "rajasthan", "sikkim", "tamil nadu", "telangana",
            "tripura", "uttar pradesh", "uttarakhand", "west bengal");

    private final RestClient geocodingClient;
    private final RestClient forecastClient;

    public WeatherLookupService(
            RestClient.Builder restClientBuilder,
            @Value("${agripulse.weather.geocoding-base-url:https://geocoding-api.open-meteo.com}") String geocodingBaseUrl,
            @Value("${agripulse.weather.forecast-base-url:https://api.open-meteo.com}") String forecastBaseUrl) {
        this.geocodingClient = restClientBuilder.baseUrl(geocodingBaseUrl).build();
        this.forecastClient = restClientBuilder.baseUrl(forecastBaseUrl).build();
    }

    public String resolveWeatherContext(String region, String providedWeatherContext) {
        if (isUsableWeatherContext(providedWeatherContext)) {
            return providedWeatherContext.trim();
        }

        if (!StringUtils.hasText(region)) {
            return "weather unavailable";
        }

        for (String variant : buildLocationVariants(region)) {
            try {
                GeocodingResponse geocodingResponse = geocodingClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/v1/search")
                                .queryParam("name", variant)
                                .queryParam("count", 5)
                                .queryParam("language", "en")
                                .queryParam("format", "json")
                                .queryParam("countryCode", "IN")
                                .build())
                        .retrieve()
                        .body(GeocodingResponse.class);

                GeoLocation match = pickBestMatch(geocodingResponse == null ? List.of() : geocodingResponse.results(), region);
                if (match == null || match.latitude() == null || match.longitude() == null) {
                    continue;
                }

                ForecastResponse forecastResponse = forecastClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/v1/forecast")
                                .queryParam("latitude", match.latitude())
                                .queryParam("longitude", match.longitude())
                                .queryParam("current", "temperature_2m,wind_speed_10m,weather_code")
                                .queryParam("timezone", "auto")
                                .build())
                        .retrieve()
                        .body(ForecastResponse.class);

                CurrentWeather current = forecastResponse == null ? null : forecastResponse.current();
                if (current == null || current.temperature2m() == null || current.windSpeed10m() == null || current.weatherCode() == null) {
                    continue;
                }

                return formatWeatherContext(match, current);
            } catch (RuntimeException exception) {
                log.debug("Weather lookup retry failed for variant='{}': {}", variant, exception.getMessage());
            }
        }

        return region.trim() + " | weather unavailable";
    }

    private boolean isUsableWeatherContext(String weatherContext) {
        if (!StringUtils.hasText(weatherContext)) {
            return false;
        }

        String normalized = weatherContext.toLowerCase(Locale.ROOT);
        return normalized.contains("|")
                && normalized.contains("wind")
                && normalized.contains("code")
                && !normalized.contains("weather unavailable")
                && !normalized.contains("try a more specific")
                && !normalized.contains("loading");
    }

    private List<String> buildLocationVariants(String region) {
        String cleaned = region.trim().replaceAll("\\s+", " ");
        Set<String> variants = new LinkedHashSet<>();
        variants.add(cleaned);

        String suffixFormatted = addIndianRegionComma(cleaned);
        if (StringUtils.hasText(suffixFormatted)) {
            variants.add(suffixFormatted);
            variants.add(suffixFormatted + ", India");
        }

        if (!cleaned.toLowerCase(Locale.ROOT).contains("india")) {
            variants.add(cleaned + ", India");
        }

        if (!cleaned.contains(",")) {
            variants.add(titleCaseWords(cleaned));
        }

        return variants.stream().filter(StringUtils::hasText).toList();
    }

    private String addIndianRegionComma(String location) {
        String normalized = location.toLowerCase(Locale.ROOT).trim();
        for (String indianRegion : INDIAN_REGIONS.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList()) {
            if (!normalized.endsWith(indianRegion)) {
                continue;
            }

            String prefix = location.substring(0, location.length() - indianRegion.length()).trim().replaceAll("[, ]+$", "");
            String formattedRegion = titleCaseWords(indianRegion);
            return StringUtils.hasText(prefix) ? titleCaseWords(prefix) + ", " + formattedRegion : formattedRegion;
        }
        return null;
    }

    private GeoLocation pickBestMatch(List<GeoLocation> results, String originalRegion) {
        if (results == null || results.isEmpty()) {
            return null;
        }

        List<String> tokens = List.of(originalRegion.toLowerCase(Locale.ROOT).split("[,\\s]+"));
        return results.stream()
                .max(Comparator
                        .comparingInt((GeoLocation location) -> "IN".equalsIgnoreCase(location.countryCode()) ? 4 : 0)
                        .thenComparingInt(location -> scoreMatch(location, tokens)))
                .orElse(results.get(0));
    }

    private int scoreMatch(GeoLocation location, List<String> tokens) {
        String combined = String.join(" ",
                safeText(location.name()),
                safeText(location.admin1()),
                safeText(location.country()))
                .toLowerCase(Locale.ROOT);

        int score = 0;
        for (String token : tokens) {
            if (token.length() < 3) {
                continue;
            }
            if (combined.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private String formatWeatherContext(GeoLocation match, CurrentWeather current) {
        String locationLabel = List.of(match.name(), match.admin1(), match.country()).stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("Matched location");

        return locationLabel
                + " | " + Math.round(current.temperature2m()) + " C"
                + " | Wind " + Math.round(current.windSpeed10m()) + " km/h"
                + " | code " + current.weatherCode();
    }

    private String titleCaseWords(String value) {
        return String.join(" ", value.trim().split("\\s+")).lines()
                .flatMap(line -> List.of(line.split("\\s+")).stream())
                .map(token -> token.isBlank()
                        ? token
                        : token.substring(0, 1).toUpperCase(Locale.ROOT) + token.substring(1).toLowerCase(Locale.ROOT))
                .reduce((left, right) -> left + " " + right)
                .orElse(value);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GeocodingResponse(List<GeoLocation> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GeoLocation(
            String name,
            String country,
            @JsonProperty("country_code") String countryCode,
            String admin1,
            Double latitude,
            Double longitude) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ForecastResponse(CurrentWeather current) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CurrentWeather(
            @JsonProperty("temperature_2m") Double temperature2m,
            @JsonProperty("wind_speed_10m") Double windSpeed10m,
            @JsonProperty("weather_code") Integer weatherCode) {
    }
}
