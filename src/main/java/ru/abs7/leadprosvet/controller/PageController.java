package ru.abs7.leadprosvet.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import ru.abs7.leadprosvet.service.BitrixInstallService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Controller
public class PageController {

    private static final Logger log = LoggerFactory.getLogger(PageController.class);

    private final BitrixInstallService bitrixInstallService;
    private volatile String cachedIndexHtml;

    public PageController(BitrixInstallService bitrixInstallService) {
        this.bitrixInstallService = bitrixInstallService;
    }

    @RequestMapping(
            value = {"/", "/bitrix/app", "/bitrix/settings"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.TEXT_HTML_VALUE
    )
    @ResponseBody
    public String app(
            @RequestParam Map<String, String> params,
            @RequestBody(required = false) String body,
            HttpServletRequest request
    ) {
        log.debug("Serving LeadProsvet UI: method={}, uri={}, remote={}",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        saveBitrixRuntimeAuthIfPresent(params, body, request);
        return indexHtml();
    }

    @GetMapping(value = "/healthz", produces = "text/plain; charset=UTF-8")
    @ResponseBody
    public String healthz() {
        return "OK";
    }

    private void saveBitrixRuntimeAuthIfPresent(Map<String, String> params, String body, HttpServletRequest request) {
        if (!looksLikeBitrixAuth(params, body)) {
            return;
        }
        try {
            bitrixInstallService.saveInstallPayload(params, body);
            log.info("Saved fresh Bitrix runtime auth from {} {}", request.getMethod(), request.getRequestURI());
        } catch (RuntimeException e) {
            log.warn("Cannot save Bitrix runtime auth from {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        }
    }

    private boolean looksLikeBitrixAuth(Map<String, String> params, String body) {
        if (params != null) {
            for (String key : params.keySet()) {
                String lower = key == null ? "" : key.toLowerCase();
                if (lower.contains("auth_id")
                        || lower.contains("refresh_id")
                        || lower.contains("access_token")
                        || lower.contains("refresh_token")
                        || lower.contains("client_endpoint")
                        || lower.contains("server_endpoint")) {
                    return true;
                }
            }
        }
        if (body == null || body.isBlank()) {
            return false;
        }
        String lowerBody = body.toLowerCase();
        return lowerBody.contains("auth_id=")
                || lowerBody.contains("refresh_id=")
                || lowerBody.contains("access_token=")
                || lowerBody.contains("refresh_token=")
                || lowerBody.contains("client_endpoint=")
                || lowerBody.contains("server_endpoint=");
    }

    private String indexHtml() {
        String current = cachedIndexHtml;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cachedIndexHtml == null) {
                cachedIndexHtml = loadIndexHtmlSafely();
            }
            return cachedIndexHtml;
        }
    }

    private String loadIndexHtmlSafely() {
        ClassPathResource resource = new ClassPathResource("static/index.html");
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Cannot load static/index.html", e);
            return fallbackHtml();
        }
    }

    private String fallbackHtml() {
        return """
                <!doctype html>
                <html lang=\"ru\">
                <head>
                    <meta charset=\"UTF-8\">
                    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
                    <title>ЛидПросвет</title>
                    <style>
                        html, body { height: 100%; margin: 0; font-family: Arial, sans-serif; background: #f5f7fb; color: #111827; }
                        body { display: grid; place-items: center; }
                        .card { width: min(720px, calc(100vw - 40px)); padding: 28px; border-radius: 20px; background: white; border: 1px solid #e5e7eb; box-shadow: 0 22px 60px rgba(15,23,42,.08); }
                        h1 { margin: 0 0 12px; font-size: 30px; }
                        p { margin: 0; color: #64748b; line-height: 1.5; }
                        code { color: #2563eb; }
                    </style>
                </head>
                <body>
                    <div class=\"card\">
                        <h1>ЛидПросвет</h1>
                        <p>Приложение запущено, но файл интерфейса <code>src/main/resources/static/index.html</code> не попал в сборку.</p>
                    </div>
                </body>
                </html>
                """;
    }
}
