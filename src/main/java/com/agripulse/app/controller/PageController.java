package com.agripulse.app.controller;

import com.agripulse.app.service.AgriService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// @Controller is similar to @RestController, but it is used for server-rendered pages.
// Instead of returning JSON, it usually returns the name of a Thymeleaf template.
@Controller
@RequiredArgsConstructor
public class PageController {

    private final AgriService agriService;

    @GetMapping("/")
    public String dashboard(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        model.addAttribute("dashboard", agriService.getDashboardData(userDetails.getUsername()));
        model.addAttribute("currentUserEmail", userDetails.getUsername());
        return "dashboard";
    }
}
