package com.agripulse.app.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// DTO stands for Data Transfer Object.
// We use this class to describe the JSON that the client sends to our API.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RiskAnalysisRequest {

    // @NotBlank rejects null, empty, or whitespace-only values before business logic runs.
    @NotBlank(message = "cropName is required.")
    @Size(max = 80, message = "cropName must be 80 characters or fewer.")
    @Pattern(regexp = "^[A-Za-z0-9 .,'()-]+$", message = "cropName contains unsupported characters.")
    private String cropName;

    @NotBlank(message = "region is required.")
    @Size(max = 80, message = "region must be 80 characters or fewer.")
    @Pattern(regexp = "^[A-Za-z0-9 .,'()-]+$", message = "region contains unsupported characters.")
    private String region;
}
