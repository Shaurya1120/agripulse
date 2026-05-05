package com.agripulse.app.dto;

import com.agripulse.app.model.RiskReport;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// This DTO shapes the JSON that our API sends back to the frontend or Postman.
// Returning a DTO keeps our API contract separate from our database entity.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RiskAnalysisResponse {

    private Long id;
    private String cropName;
    private String region;
    private String riskLevel;
    private String mitigationStrategy;

    public static RiskAnalysisResponse fromEntity(RiskReport riskReport) {
        return new RiskAnalysisResponse(
                riskReport.getId(),
                riskReport.getCropName(),
                riskReport.getRegion(),
                riskReport.getRiskLevel(),
                riskReport.getMitigationStrategy()
        );
    }
}

