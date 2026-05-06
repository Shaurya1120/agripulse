package com.agripulse.app.controller;

import com.agripulse.app.dto.RiskAnalysisRequest;
import com.agripulse.app.dto.RiskAnalysisResponse;
import com.agripulse.app.dto.RiskReferenceResponse;
import com.agripulse.app.service.AgriService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// @RestController tells Spring that this class handles HTTP requests
// and that method return values should be written directly as JSON.
@RestController
// @RequestMapping adds a shared URL prefix for all endpoints in this controller.
@RequestMapping("/api/risk")
@RequiredArgsConstructor
public class AgriController {

    private final AgriService agriService;

    // @PostMapping means this method responds to HTTP POST requests.
    // The full URL becomes /api/risk/analyze because it combines with @RequestMapping above.
    @PostMapping("/analyze")
    public ResponseEntity<RiskAnalysisResponse> analyzeRisk(
            // @RequestBody tells Spring to read the incoming JSON body
            // and convert it into a Java object for us.
            @Valid @RequestBody RiskAnalysisRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        return ResponseEntity.ok(agriService.analyzeRisk(request, userDetails.getUsername()));
    }

    @GetMapping("/reference")
    public ResponseEntity<RiskReferenceResponse> getReferenceData(
            @RequestParam String cropName,
            @RequestParam String region,
            @RequestParam(defaultValue = "Enterprise") String stakeholderType) {
        return ResponseEntity.ok(agriService.getReferenceData(cropName, region, stakeholderType));
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(agriService.getHistoryPage(userDetails.getUsername(), page, size));
    }
}
