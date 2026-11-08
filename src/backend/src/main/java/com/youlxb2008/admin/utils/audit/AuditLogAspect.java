package com.youlxb2008.admin.utils.audit;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import com.youlxb2008.admin.modules.system.entity.AuditLog;
import com.youlxb2008.admin.modules.system.service.AuditLogService;
import com.youlxb2008.admin.utils.jwt.context.UserContext;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 审计日志切面
 * 拦截所有 Controller 方法，记录操作日志到数据库
 * 仅记录 POST/PUT/DELETE 等写操作；GET 查询不记录，避免日志膨胀
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogService auditLogService;

    /**
     * 环绕通知：拦截 modules 包下所有 Controller 的方法
     * 流程：
     * 1. 获取 HTTP 请求信息（方法、路径、IP）
     * 2. 仅对写操作（POST/PUT/DELETE）记录
     * 3. 从 @Operation 注解读取接口描述
     * 4. 序列化入参（过滤 MultipartFile、HttpServletRequest）
     * 5. 从 UserContext 读取操作人
     * 6. 执行目标方法，统计耗时与成功/失败
     * 7. 异步落库（失败不影响主流程）
     */
    @Around("execution(* com.youlxb2008.admin.modules..controller..*.*(..))")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 获取请求信息；非 HTTP 场景直接放行
        HttpServletRequest request = getRequest();
        if (request == null) {
            return joinPoint.proceed();
        }

        // 2. 只记录写操作（POST/PUT/DELETE），GET 不记录
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method)
                && !"PUT".equalsIgnoreCase(method)
                && !"DELETE".equalsIgnoreCase(method)) {
            return joinPoint.proceed();
        }

        // 3. 构造审计日志主体
        AuditLog auditLog = new AuditLog();
        auditLog.setMethod(method);
        auditLog.setPath(request.getRequestURI());
        auditLog.setIpAddress(getClientIp(request));

        // 从 @Operation 注解 summary 读取接口描述
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Operation operation = signature.getMethod().getAnnotation(Operation.class);
        if (operation != null && StrUtil.isNotBlank(operation.summary())) {
            auditLog.setDescription(operation.summary());
        }

        // 序列化入参（过滤 MultipartFile、HttpServletRequest 等不可序列化对象）
        try {
            Object[] args = joinPoint.getArgs();
            if (args != null && args.length > 0) {
                Map<String, Object> safeArgs = new HashMap<>();
                for (int i = 0; i < args.length; i++) {
                    Object arg = args[i];
                    if (arg instanceof MultipartFile) {
                        // 文件类型只记录原始文件名
                        safeArgs.put("file", ((MultipartFile) arg).getOriginalFilename());
                    } else if (arg instanceof HttpServletRequest) {
                        // HttpServletRequest 跳过，避免序列化整个请求
                        continue;
                    } else if (arg != null) {
                        safeArgs.put("arg" + i, arg);
                    }
                }
                auditLog.setParams(JSONUtil.toJsonStr(safeArgs));
            }
        } catch (Exception e) {
            // 序列化失败仅 debug 记录，不中断主流程
            log.debug("序列化参数失败: {}", e.getMessage());
        }

        // 4. 获取操作人（登录接口本身可能未登录，忽略异常）
        try {
            var ctx = UserContext.get();
            if (ctx != null && ctx.getUser() != null) {
                auditLog.setUserId(ctx.getUser().getId());
                auditLog.setUsername(ctx.getUser().getUsername());
            }
        } catch (Exception e) {
            // 未登录场景（如登录接口本身），忽略
        }

        // 5. 执行目标方法，统计耗时与成败
        long startTime = System.currentTimeMillis();
        Object result;
        boolean success = true;
        try {
            result = joinPoint.proceed();
        } catch (Throwable e) {
            // 抛异常视为失败
            success = false;
            throw e;
        } finally {
            // 6. 记录耗时和结果
            auditLog.setDuration(System.currentTimeMillis() - startTime);
            auditLog.setSuccess(success ? 1 : 0);
            auditLog.setCreatedAt(new Date());

            // 7. 落库；失败仅告警，不影响主流程
            try {
                auditLogService.save(auditLog);
            } catch (Exception e) {
                log.warn("保存审计日志失败: {}", e.getMessage());
            }
        }

        return result;
    }

    /**
     * 从 RequestContextHolder 获取当前 HTTP 请求
     * @return 当前请求；非 HTTP 调用场景返回 null
     */
    private HttpServletRequest getRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes) {
            return ((ServletRequestAttributes) attrs).getRequest();
        }
        return null;
    }

    /**
     * 获取客户端真实 IP（支持反向代理透传）
     * 优先级：X-Forwarded-For > X-Real-IP > RemoteAddr
     * @param request HTTP 请求
     * @return 客户端 IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
