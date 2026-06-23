package ru.abs7.leadprosvet.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.abs7.leadprosvet.config.AppProperties;

import java.time.OffsetDateTime;

@Controller
public class PageController {

    private static final Logger log = LoggerFactory.getLogger(PageController.class);
    private final AppProperties appProperties;

    public PageController(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @GetMapping({"/", "/bitrix/app", "/bitrix/settings"})
    public String app(Model model) {
        model.addAttribute("appName", appProperties.name());
        model.addAttribute("appVersion", appProperties.version());
        model.addAttribute("baseUrl", appProperties.baseUrl());
        model.addAttribute("serverTime", OffsetDateTime.now().toString());
        return "app";
    }

    @GetMapping("/healthz")
    public String healthz(Model model) {
        model.addAttribute("appName", appProperties.name());
        model.addAttribute("appVersion", appProperties.version());
        log.debug("Health page requested");
        return "healthz";
    }
}
