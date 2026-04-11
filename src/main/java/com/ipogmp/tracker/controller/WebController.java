package com.ipogmp.tracker.controller;

import com.ipogmp.tracker.service.IpoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * MVC controller — renders Thymeleaf HTML templates.
 */
@Controller
@RequiredArgsConstructor
public class WebController {

    private final IpoService ipoService;

    /** Public dashboard page */
    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("ipos", ipoService.getAllIpos());
        model.addAttribute("activeCount", ipoService.getActiveIpos().size());
        return "index";
    }

    /** Admin panel (secured via Spring Security) */
    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("ipos", ipoService.getAllIpos());
        return "admin";
    }

    /** Login page */
    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
