package com.agripulse.app.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

// @Component makes this class a Spring-managed object.
// Because Spring manages it, we can inject it into other beans and expose it as an AI tool.
@Component
@Slf4j
public class EmergencyAlertTool {

    // @Tool exposes this Java method to the AI model as a callable function.
    // The model does not run Java directly. It asks Spring AI to invoke this method.
    @Tool(description = "Send an emergency alert when an agricultural supply chain risk is high.")
    public String sendEmergencyAlert(
            @ToolParam(description = "Name of the crop under risk") String cropName,
            @ToolParam(description = "Region where the risk is happening") String region,
            @ToolParam(description = "Risk level. Use High when the situation is severe") String riskLevel,
            @ToolParam(description = "Fallback or Plan B mitigation strategy") String mitigationStrategy) {

        if (!"High".equalsIgnoreCase(riskLevel)) {
            return "No emergency alert was logged because the risk level was not High.";
        }

        log.warn("HIGH RISK ALERT -> crop: {}, region: {}, riskLevel: {}, mitigationStrategy: {}",
                cropName, region, riskLevel, mitigationStrategy);

        return "Emergency alert logged successfully for crop " + cropName + " in region " + region + ".";
    }
}
