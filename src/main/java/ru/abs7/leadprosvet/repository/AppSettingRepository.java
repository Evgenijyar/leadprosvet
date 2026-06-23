package ru.abs7.leadprosvet.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.abs7.leadprosvet.domain.AppSetting;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
