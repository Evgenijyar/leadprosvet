package ru.abs7.leadprosvet.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Controller
public class PageController {

    private static final Logger log = LoggerFactory.getLogger(PageController.class);

    private final String indexHtml;

    public PageController() {
        this.indexHtml = loadIndexHtml();
    }

    @RequestMapping(
            value = {"/", "/bitrix/app", "/bitrix/settings"},
            method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.TEXT_HTML_VALUE
    )
    @ResponseBody
    public String app(HttpServletRequest request) {
        log.debug("Serving LeadProsvet UI: method={}, uri={}, remote={}",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        return indexHtml;
    }

    @GetMapping(value = "/healthz", produces = "text/plain; charset=UTF-8")
    @ResponseBody
    public String healthz() {
        log.debug("Health check requested");
        return "OK";
    }

    private String loadIndexHtml() {
        ClassPathResource resource = new ClassPathResource("static/index.html");
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load static/index.html", e);
        }
    }
}
