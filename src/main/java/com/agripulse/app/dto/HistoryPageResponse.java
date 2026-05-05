package com.agripulse.app.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

// This DTO returns paginated history data for the frontend table.
@Getter
@AllArgsConstructor
public class HistoryPageResponse {

    private List<RiskAnalysisResponse> items;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}

