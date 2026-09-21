package com.gptr.common.repository;

import com.gptr.common.config.AppConfigEntry;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 全局配置仓库（api CRUD / worker 启动读取共用）。
 */
public interface AppConfigRepository extends JpaRepository<AppConfigEntry, String> {
}
