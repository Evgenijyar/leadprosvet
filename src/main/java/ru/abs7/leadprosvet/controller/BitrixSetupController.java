package ru.abs7.leadprosvet.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.abs7.leadprosvet.service.bitrix.BitrixSetupService;

import java.util.Map;

@RestController
@RequestMapping("/api/bitrix/setup")
public class BitrixSetupController {

    private final BitrixSetupService bitrixSetupService;

    public BitrixSetupController(BitrixSetupService bitrixSetupService) {
        this.bitrixSetupService = bitrixSetupService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(bitrixSetupService.status());
    }

    @GetMapping("/create-ai-field")
    public ResponseEntity<Map<String, Object>> createAiFieldGet() {
        return ResponseEntity.ok(bitrixSetupService.createAiLeadField());
    }

    @PostMapping("/create-ai-field")
    public ResponseEntity<Map<String, Object>> createAiFieldPost() {
        return ResponseEntity.ok(bitrixSetupService.createAiLeadField());
    }

    @GetMapping("/bind-lead-event")
    public ResponseEntity<Map<String, Object>> bindLeadEventGet() {
        return ResponseEntity.ok(bitrixSetupService.bindLeadAddEvent());
    }

    @PostMapping("/bind-lead-event")
    public ResponseEntity<Map<String, Object>> bindLeadEventPost() {
        return ResponseEntity.ok(bitrixSetupService.bindLeadAddEvent());
    }

    @GetMapping("/run")
    public ResponseEntity<Map<String, Object>> runGet() {
        return ResponseEntity.ok(bitrixSetupService.runInitialSetup());
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runPost() {
        return ResponseEntity.ok(bitrixSetupService.runInitialSetup());
    }
}
