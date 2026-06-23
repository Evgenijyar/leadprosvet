package ru.abs7.leadprosvet.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class PageController {

    private static final Logger log = LoggerFactory.getLogger(PageController.class);

    @GetMapping({"/bitrix/app", "/bitrix/settings"})
    public String app() {
        return "forward:/index.html";
    }

    @GetMapping(value = "/healthz", produces = "text/plain; charset=UTF-8")
    @ResponseBody
    public String healthz() {
        log.debug("Health check requested");
        return "OK";
    }
}
