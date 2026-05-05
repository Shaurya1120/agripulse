package com.agripulse.app.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

// This DTO carries starter data from the controller to the Thymeleaf page.
@Getter
@AllArgsConstructor
public class UiDashboardData {

    private List<RiskMapPoint> riskMapPoints;
    private List<RiskAnalysisResponse> recentReports;
}

