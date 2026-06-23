package ru.abs7.leadprosvet.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.abs7.leadprosvet.config.AppProperties;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
public class BitrixInstallController {

    private static final Logger log = LoggerFactory.getLogger(BitrixInstallController.class);
    private final AppProperties appProperties;

    public BitrixInstallController(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @GetMapping(value = "/bitrix/install", produces = MediaType.TEXT_HTML_VALUE)
    public String installGet(@RequestParam Map<String, String> params, HttpServletRequest request) {
        log.info("Bitrix install GET from ip={}, params={}", request.getRemoteAddr(), safeParams(params));
        return installHtml("GET");
    }

    @PostMapping(value = "/bitrix/install", consumes = MediaType.ALL_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public String installPost(
            @RequestParam Map<String, String> params,
            @RequestBody(required = false) String body,
            HttpServletRequest request
    ) {
        log.info("Bitrix install POST from ip={}, params={}, body={}", request.getRemoteAddr(), safeParams(params), safeBody(body));
        return installHtml("POST");
    }

    private String installHtml(String method) {
        return """
                <!doctype html>
                <html lang=\"ru\">
                <head>
                    <meta charset=\"UTF-8\">
                    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
                    <title>ЛидПросвет — установка</title>
                    <link rel=\"stylesheet\" href=\"/css/app.css\">
                </head>
                <body>
                <div class=\"install-page\">
                    <div class=\"install-card\">
                        <img class=\"install-logo\" src=\"/logoHorizontal.png\" alt=\"ЛидПросвет\" onerror=\"this.style.display='none'\">
                        <h1>ЛидПросвет</h1>
                        <p>Endpoint установки доступен. Метод: <strong>%s</strong>.</p>
                        <p class=\"muted\">Следующим этапом здесь будет сохранение OAuth-данных и завершение установки Bitrix24-приложения.</p>
                        <div class=\"install-meta\">%s · %s</div>
                    </div>
                </div>
                </body>
                </html>
                """.formatted(method, appProperties.baseUrl(), OffsetDateTime.now());
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
