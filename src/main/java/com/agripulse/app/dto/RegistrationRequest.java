package com.agripulse.app.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RegistrationRequest {

    @NotBlank(message = "Full name is required.")
    @Size(max = 120, message = "Full name must be 120 characters or fewer.")
    private String fullName;

    @NotBlank(message = "Email is required.")
    @Email(message = "Enter a valid email address.")
    @Size(max = 160, message = "Email must be 160 characters or fewer.")
    private String email;

    @NotBlank(message = "Password is required.")
    @Size(min = 8, max = 80, message = "Password must be between 8 and 80 characters.")
    @Pattern(regexp = ".*[A-Za-z].*", message = "Password must include at least one letter.")
    @Pattern(regexp = ".*\\d.*", message = "Password must include at least one number.")
    private String password;
}
