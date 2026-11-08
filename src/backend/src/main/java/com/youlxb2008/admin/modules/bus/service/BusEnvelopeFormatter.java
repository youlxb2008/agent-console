package com.youlxb2008.admin.modules.bus.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * 消息信封格式化（M1，方案 §7）
 * <p>
 * dispatcher 注入 WS 的消息体（收件 agent 按此格式识别来源 / 程序化去重 / 知晓模型）：
 * <pre>
 * [来源:{fromName}(agent:{fromId})|类型:{type}|主题:{topic}|消息:{messageId}|模型:{model或默认}]
 * {title}
 * {payload JSON 序列化}
 * </pre>
 * payload 为空时省略第三行。纯函数，可单测。
 */
@Slf4j
public final class BusEnvelopeFormatter {

    /** 模型字段占位（消息未指定模型时） */
    public static final String MODEL_DEFAULT_LABEL = "默认";

    /**
     * notify/request 注入文末处置指引行（busfix-1007）：
     * 提醒收件 agent 对交付/回执类消息主动核验交付物并同 topic 回投确认。
     */
    public static final String DELIVERY_HINT =
            "[系统提示] 若本条为交付/回执类消息：请主动核验交付物（AC 笔记/台账/落盘证据），"
                    + "完成后同 topic 回投确认（expectReplyMinutes=0）。";

    /** 追加处置指引的消息类型（busfix-1007：notify/request；alert 不追加） */
    private static final Set<String> HINT_TYPES = Set.of("notify", "request");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BusEnvelopeFormatter() {
    }

    /**
     * 格式化信封文本
     *
     * @param fromName  生产者展示名（如 alpha）
     * @param fromId    生产者 agent id
     * @param type      类型 notify/alert
     * @param topic     场景标签
     * @param messageId 幂等键（UUID）
     * @param model     指定模型（空 = 默认）
     * @param title     标题
     * @param payload   消息体 JSON 字符串（可为空）
     */
    public static String format(String fromName, long fromId, String type, String topic,
            String messageId, String model, String title, String payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("[来源:").append(fromName).append("(agent:").append(fromId)
                .append(")|类型:").append(type)
                .append("|主题:").append(topic)
                .append("|消息:").append(messageId)
                .append("|模型:").append(model == null || model.isBlank() ? MODEL_DEFAULT_LABEL : model)
                .append("]\n");
        sb.append(title == null ? "" : title);
        if (payload != null && !payload.isBlank()) {
            sb.append("\n").append(payload);
        }
        if (type != null && HINT_TYPES.contains(type)) {
            sb.append("\n").append(DELIVERY_HINT);
        }
        return sb.toString();
    }

    /**
     * payload 对象 → JSON 字符串（校验链用：序列化后计字节数判 ≤8KB）
     * 对象为 null 返回 null；已字符串则原样返回。
     */
    public static String serializePayload(Object payload) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof String s) {
            return s;
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload 序列化失败: " + e.getMessage());
        }
    }

    /**
     * 判断（已序列化的）payload 顶层 content 是否空白（busfix-1007 入队告警判据）：
     * payload 整体为空 / 非法 JSON / 顶层无 content 键 / content 为 null 或空白字符串 → true；
     * content 为非字符串 JSON 值（对象/数组/数字等）视为有内容。纯函数，可单测。
     */
    public static boolean isContentBlank(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return true;
        }
        try {
            JsonNode content = MAPPER.readTree(payloadJson).get("content");
            if (content == null || content.isNull()) {
                return true;
            }
            return content.isTextual() && content.asText().isBlank();
        } catch (JsonProcessingException e) {
            return true;
        }
    }
}
