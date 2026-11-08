package com.youlxb2008.admin.modules.note.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理端：笔记全文预览响应（v3 迁入 note 域，字段调整）
 * <p>
 * v3：contributors（贡献者多值）已上移至父类 {@link NoteContentVO}
 * （🔥 取代 v2 的 agentName 单值）；本类仅作管理端语义标记，便于 Swagger 分组。
 * JWT 可信域：返回完整 Markdown 原文供预览抽屉全文审阅。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminNoteContentVO extends NoteContentVO {
}
