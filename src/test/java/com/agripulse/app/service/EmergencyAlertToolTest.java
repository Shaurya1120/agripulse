package com.agripulse.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class EmergencyAlertToolTest {

    private final EmergencyAlertTool emergencyAlertTool = new EmergencyAlertTool();

    @Test
    void sendEmergencyAlertLogsWarning(CapturedOutput output) {
        String result = emergencyAlertTool.sendEmergencyAlert(
                "Wheat",
                "Punjab",
                "High",
                "Use alternate suppliers immediately."
        );

        assertThat(result).contains("Emergency alert logged successfully");
        assertThat(output.getOut()).contains("HIGH RISK ALERT");
        assertThat(output.getOut()).contains("Wheat");
        assertThat(output.getOut()).contains("Punjab");
    }
}
