package ru.abs7.leadprosvet.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import ru.abs7.leadprosvet.config.AppProperties;

import java.time.OffsetDateTime;
import java.util.Map;

@Controller
public class BitrixInstallController {

    private static final Logger log = LoggerFactory.getLogger(BitrixInstallController.class);
    private final AppProperties appProperties;

    public BitrixInstallController(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @GetMapping("/bitrix/install")
    public String installGet(@RequestParam Map<String, String> params, HttpServletRequest request, Model model) {
        log.info("Bitrix install GET from ip={}, params={}", request.getRemoteAddr(), safeParams(params));
        fillModel(model, "GET", params, null);
        return "install";
    }

    @PostMapping(value = "/bitrix/install", consumes = MediaType.ALL_VALUE)
    public String installPost(
            @RequestParam Map<String, String> params,
            @RequestBody(required = false) String body,
            HttpServletRequest request,
            Model model
    ) {
        log.info("Bitrix install POST from ip={}, params={}, body={}", request.getRemoteAddr(), safeParams(params), safeBody(body));
        fillModel(model, "POST", params, body);
        return "install";
    }

    private void fillModel(Model model, String method, Map<String, String> params, String body) {
        model.addAttribute("appName", appProperties.name());
        model.addAttribute("appVersion", appProperties.version());
        model.addAttribute("baseUrl", appProperties.baseUrl());
        model.addAttribute("method", method);
        model.addAttribute("params", safeParams(params));
        model.addAttribute("body", safeBody(body));
        model.addAttribute("serverTime", OffsetDateTime.now().toString());
    }

    private Map<String, String> safeParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        return params.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                entry -> maskIfSecret(entry.getKey(), entry.getValue())
        ));
    }

    private String safeBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return body
                .replaceAll("(?i)(access_token=)[^&\\s]+", "$1***")
                .replaceAll("(?i)(refresh_token=)[^&\\s]+", "$1***")
                .replaceAll("(?i)(auth=)[^&\\s]+", "$1***")
                .replaceAll("(?i)(application_token=)[^&\\s]+", "$1***");
    }

    private String maskIfSecret(String key, String value) {
        if (value == null) {
            return "";
        }
        String lowerKey = key == null ? "" : key.toLowerCase();
        if (lowerKey.contains("token") || lowerKey.contains("auth") || lowerKey.contains("key")) {
            return "***";
        }
        return value;
    }
}
