package com.agripulse.app.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

// Each point summarizes how many reports a region has and what the latest risk looks like.
@Getter
@AllArgsConstructor
public class RiskMapPoint {

    private String region;
    private long totalReports;
    private long highRiskReports;
    private String latestRiskLevel;
}

