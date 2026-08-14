package com.ensp.nms.security;

import com.ensp.nms.entity.Role;
import com.ensp.nms.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService("ensp-nms-test-jwt-secret-key-32bytes!", 3600_000);
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
