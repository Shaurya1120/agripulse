package com.agripulse.app.dto;

import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

// This DTO gives clients a clean, predictable error response
// instead of a large default stack trace page.
@Getter
@AllArgsConstructor
public class ApiErrorResponse {

    private Instant timestamp;
    private int status;
    private String error;
    private String message;
    private List<String> details;
}

