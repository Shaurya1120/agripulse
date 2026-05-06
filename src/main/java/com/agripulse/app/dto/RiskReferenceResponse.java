package com.agripulse.app.dto;

import java.math.BigDecimal;

// This DTO sends trusted reference data to the frontend before a full AI analysis runs.
// We use it to prefill live weather and official mandi price signals.
public record RiskReferenceResponse(
        String cropName,
        String region,
        String stakeholderType,
        String verifiedWeatherContext,
        boolean weatherAvailable,
        BigDecimal marketPricePerKgInr,
        String marketLocation,
        String marketSource,
        String marketSummary
) {
}
