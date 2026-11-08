package com.youlxb2008.admin.utils.jwt.resolver;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.youlxb2008.admin.exceptions.UnauthorizedException;
import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.utils.jwt.annotation.CurrentUser;
import com.youlxb2008.admin.utils.jwt.context.UserContext;

/**
 * {@code @CurrentUser} 注解的参数解析器
 * <p>
 * 从 ThreadLocal（UserContext）读取当前登录用户，注入到 Controller 方法参数。
 * 未登录时抛出 UnauthorizedException（401），保证方法内一定能拿到非空 User。
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    /**
     * 判断是否支持该参数：参数类型为 User 且标注了 {@code @CurrentUser}
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterAnnotation(CurrentUser.class) != null
                && parameter.getParameterType().equals(User.class);
    }

    /**
     * 解析参数：从 UserContext（ThreadLocal）获取当前用户
     *
     * @throws UnauthorizedException 未登录时抛出 401
     */
    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        User user = UserContext.getUser();
        if (user == null) {
            throw new UnauthorizedException("未登录或登录失效，请重新登录");
        }
        return user;
    }
}
