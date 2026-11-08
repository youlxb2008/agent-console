package com.youlxb2008.admin.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;

/**
 * 数据库初始化器（schema.sql + data.sql）
 * <p>
 * 替代原 AutoDDL + RuntimeDbInitializer 两个类，总代码量 ~60 行 vs 原 ~900 行。
 * <p>
 * 工作原理：
 * 1. 启动时执行 classpath:schema.sql（CREATE TABLE IF NOT EXISTS，幂等建表）
 * 2. 启动时执行 classpath:data.sql（每条 INSERT 带 WHERE NOT EXISTS 守卫，幂等种子）
 * 3. 启动时执行 classpath:db/mysql/agent-domain.sql（agent 域 4 表 + 权限，幂等）
 * 4. 已有数据库重启无副作用
 * <p>
 * 注意：本项目使用 dynamic-datasource，Spring Boot 默认的 spring.sql.init
 * 可能拿不到 primary 数据源，因此显式注入 primary DataSource 主动执行更可靠。
 * <p>
 * 执行时机：Order(50) 保证在业务 Bean 使用数据库前完成
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(50)
public class SqlInitializer implements ApplicationRunner {

    /** 注入 primary 数据源（dynamic-datasource 会把主数据源暴露为默认 Bean） */
    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        // schema.sql 必须成功，否则 data.sql 无表可插
        boolean schemaOk = executeScript("schema.sql");
        if (schemaOk) {
            executeScript("data.sql");
        } else {
            log.error(">>> schema.sql 执行失败，跳过 data.sql");
        }
        // agent 域表 + 权限（幂等：IF NOT EXISTS / WHERE NOT EXISTS）
        // 顺序敏感：必须在 data.sql 之后——data.sql 超管权限自动同步的守卫是
        // "role_id=1 无任何权限行"，若先插 agent 域权限会导致基础权限同步被跳过
        executeScript("db/mysql/agent-domain.sql");

        // 存量库幂等补列（v3/v3.5 新增列）
        // 背景：CREATE TABLE IF NOT EXISTS 对已存在的旧表不生效，v3.5 之前创建的
        // H2 dev 文件库缺 dir_path/notebook_id 列，笔记列表查询直接报"列不存在"
        // （故 agent-domain.sql 文件头注释要求存量 H2 库删库重建——此处兜底免删库）
        ensureColumn("t_note_meta", "dir_path", "VARCHAR(512) NOT NULL DEFAULT ''");
        ensureColumn("t_note_meta", "notebook_id", "BIGINT NOT NULL DEFAULT 0");
        // v4.1：t_api_key 新增明文列（管理端历史 key 可随时查看/复制；存量行 NULL=旧 key 未存明文）
        ensureColumn("t_api_key", "key_plain", "VARCHAR(128) NULL");
        // v4.3：t_agent 新增头像列（管理端上传头像 → 门户卡片展示；存量行 NULL=无头像走色块兜底）
        ensureColumn("t_agent", "avatar", "VARCHAR(255) NULL");
    }

    /**
     * 幂等补列：探测目标表缺列时自动 ALTER TABLE ADD COLUMN（已存在则跳过）
     * <p>
     * INFORMATION_SCHEMA 探测 + ALTER TABLE ADD COLUMN 语法在 H2（MODE=MySQL）
     * 与 MySQL 5.7 通用，故可放在与方言无关的初始化流程中；
     * 不用 "ADD COLUMN IF NOT EXISTS" 是因为 MySQL 5.7 不支持该语法。
     *
     * @param table  表名
     * @param column 列名
     * @param ddl    列定义（不含列名，如 "BIGINT NOT NULL DEFAULT 0"）
     */
    private void ensureColumn(String table, String column, String ddl) {
        // 探测 SQL：统计该表该列的元数据行数，0=缺列（UPPER 比较屏蔽 H2/MySQL 大小写差异）
        String probe = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE UPPER(TABLE_NAME) = ? AND UPPER(COLUMN_NAME) = ?";
        try (var conn = dataSource.getConnection();
                var ps = conn.prepareStatement(probe)) {
            ps.setString(1, table.toUpperCase());
            ps.setString(2, column.toUpperCase());
            try (var rs = ps.executeQuery()) {
                rs.next();
                // 缺列才补：加列带默认值，存量行自动填充默认值（dir_path=''、notebook_id=0）
                if (rs.getInt(1) == 0) {
                    // 表名/列名来自代码内固定常量（非用户输入），拼接无注入风险
                    String alter = "ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + ddl;
                    try (var st = conn.createStatement()) {
                        st.execute(alter);
                    }
                    log.info(">>> SqlInitializer 补列完成: {}.{}", table, column);
                }
            }
        } catch (Exception e) {
            // 补列失败只记录不中断启动：让业务报错暴露问题而非掩盖（fail-loud）
            log.error(">>> SqlInitializer 补列失败 {}.{}: {}", table, column, e.getMessage(), e);
        }
    }

    /**
     * 执行 classpath 下的 SQL 脚本
     *
     * @param classpathResource classpath 路径，如 "schema.sql"
     * @return true=执行成功，false=执行失败
     */
    private boolean executeScript(String classpathResource) {
        Resource resource = new ClassPathResource(classpathResource);
        if (!resource.exists()) {
            log.warn(">>> SqlInitializer 未找到 {}", classpathResource);
            return false;
        }
        try {
            // 显式指定 UTF-8 编码读取 SQL 文件，避免 Windows 默认 GBK 导致中文乱码
            // H2 对 MySQL 语法的反引号、UNIQUE KEY 等都兼容（MODE=MySQL）
            try (var conn = dataSource.getConnection()) {
                EncodedResource encoded = new EncodedResource(resource, StandardCharsets.UTF_8);
                ScriptUtils.executeSqlScript(conn, encoded);
            }
            log.info(">>> SqlInitializer 执行完成: {}", classpathResource);
            return true;
        } catch (Exception e) {
            log.error(">>> SqlInitializer 执行失败 {}: {}", classpathResource, e.getMessage(), e);
            return false;
        }
    }
}
