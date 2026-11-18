package com.ensp.nms.security;

import com.ensp.nms.entity.Role;
import com.ensp.nms.entity.User;
import com.ensp.nms.service.AuthorityVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        // 令牌里带 authorityVersion：用桩对象固定为 0，令牌校验才可复现
        AuthorityVersionService versionService = mock(AuthorityVersionService.class);
        when(versionService.current()).thenReturn(0L);
        jwtService = new JwtService("ensp-nms-test-jwt-secret-key-32bytes!", 3600_000, versionService);
    }

    @Test
    void generateAndParseToken() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setRealName("系统管理员");
        Role role = new Role();
        role.setName("ADMIN");
        user.setRoles(Set.of(role));

        String token = jwtService.generateToken(user);
        assertNotNull(token);
        assertTrue(jwtService.isValid(token));
        assertEquals("admin", jwtService.getUsername(token));
        assertTrue(jwtService.getRoles(token).contains("ADMIN"));
    }

    @Test
    void invalidTokenRejected() {
        assertFalse(jwtService.isValid("not.a.jwt"));
    }
}
