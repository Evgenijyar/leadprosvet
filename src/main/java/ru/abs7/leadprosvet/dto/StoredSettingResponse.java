package ru.abs7.leadprosvet.dto;

import java.time.OffsetDateTime;

public record StoredSettingResponse(
        boolean ok,
        String key,
        OffsetDateTime savedAt
) {
}
