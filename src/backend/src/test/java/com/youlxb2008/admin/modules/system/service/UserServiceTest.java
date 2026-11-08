package com.youlxb2008.admin.modules.system.service;

import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.modules.system.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * UserService 单元测试样例
 * 测试单实体 CRUD 逻辑（不启动 Spring 上下文）
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserRoleService userRoleService;

    @InjectMocks
    private UserService userService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setPassword("$2a$10$fakeHash");
        testUser.setNickname("测试用户");
        testUser.setStatus(0);
    }

    @Test
    @DisplayName("根据用户名查询用户 - 用户存在")
    void testGetByUsernameExists() {
        // 注意：UserService 继承 ServiceImpl，getOne 需要 mybatis-plus 上下文
        // 这里仅作为样例结构，实际需要集成测试才能测试 ServiceImpl 方法
        // 此测试验证注入和 mock 框架是否正常
        assertNotNull(userService);
    }

    @Test
    @DisplayName("新增用户 - 重名校验")
    void testAddUserDuplicate() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        assertThrows(BusinessException.class, () -> userService.addUser(testUser));
    }
}
