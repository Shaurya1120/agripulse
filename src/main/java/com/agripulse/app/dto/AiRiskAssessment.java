package com.agripulse.app.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// This DTO represents the structured answer we want back from the AI model.
// It is not stored in the database. It is only used between the service and the AI layer.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiRiskAssessment {

    private String riskLevel;
    private String mitigationStrategy;
}

