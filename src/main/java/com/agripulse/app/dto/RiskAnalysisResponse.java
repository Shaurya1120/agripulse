package com.agripulse.app.dto;

import com.agripulse.app.model.RiskReport;
import java.math.BigDecimal;
import java.util.List;
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
    private String stakeholderType;
    private String disruptionSummary;
    private String primaryThreat;
    private String detailedProblem;
    private List<String> riskFactors;
    private List<String> enterpriseActions;
    private List<String> farmerActions;
    private List<String> governmentSchemes;
    private Integer expectedSupplyImpactPercent;
    private Integer expectedPriceIncreasePercent;
    private Integer estimatedLossPercent;
    private BigDecimal quantityTonnes;
    private BigDecimal cropRatePerKgInr;
    private BigDecimal farmAreaAcres;
    private Integer planningHorizonDays;
    private BigDecimal estimatedLossInr;

    public static RiskAnalysisResponse fromEntity(RiskReport riskReport) {
        RiskAnalysisResponse response = new RiskAnalysisResponse();
        response.setId(riskReport.getId());
        response.setCropName(riskReport.getCropName());
        response.setRegion(riskReport.getRegion());
        response.setRiskLevel(riskReport.getRiskLevel());
        response.setMitigationStrategy(riskReport.getMitigationStrategy());
        response.setRiskFactors(List.of());
        response.setEnterpriseActions(List.of());
        response.setFarmerActions(List.of());
        response.setGovernmentSchemes(List.of());
        return response;
    }
}
