package com.youlxb2008.admin.modules.system.controller;

import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.youlxb2008.admin.modules.system.entity.AuditLog;
import com.youlxb2008.admin.modules.system.service.AuditLogService;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;
import com.youlxb2008.admin.utils.page.BPage;
import com.youlxb2008.admin.utils.page.PageSearch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 审计日志 Controller
 * 提供操作日志的分页查询能力，供安全审计与问题排查使用
 * 需要 system:audit 权限
 */
@Slf4j
@RestController
@RequestMapping("/system/audit")
@RequiredArgsConstructor
@Tag(name = "审计日志", description = "操作日志查询")
public class AuditLogController {

    private final AuditLogService auditLogService;

    /**
     * 分页查询审计日志
     * @param query      查询条件（username 模糊、path 模糊、success 精确）
     * @param pageSearch 分页参数
     * @return 分页数据
     */
    @Operation(summary = "分页查询审计日志")
    @RequiresPermissions("system:audit")
    @GetMapping("/list")
    public ResultVO list(AuditLog query, PageSearch pageSearch) {
        Page<AuditLog> page = auditLogService.page(
            pageSearch.toDBPage(),
            Wrappers.<AuditLog>lambdaQuery()
                // 用户名模糊匹配（仅当传入非空时生效）
                .like(StringUtils.hasText(query.getUsername()), AuditLog::getUsername, query.getUsername())
                // 请求路径模糊匹配
                .like(StringUtils.hasText(query.getPath()), AuditLog::getPath, query.getPath())
                // 成功/失败精确匹配
                .eq(query.getSuccess() != null, AuditLog::getSuccess, query.getSuccess())
                // 按 id 倒序，最新日志在最前
                .orderByDesc(AuditLog::getId)
        );
        return ResultVO.getSuccessVO("查询成功").setData(BPage.of(page));
    }
}
