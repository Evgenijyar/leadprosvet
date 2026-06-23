package ru.abs7.leadprosvet.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.abs7.leadprosvet.service.queue.LeadProcessingQueueService;

import java.util.Map;

@RestController
@RequestMapping("/api/bitrix/queue")
public class LeadQueueController {

    private final LeadProcessingQueueService queueService;

    public LeadQueueController(LeadProcessingQueueService queueService) {
        this.queueService = queueService;
    }

    @GetMapping({"", "/status", "/jobs"})
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(queueService.status());
    }
}
