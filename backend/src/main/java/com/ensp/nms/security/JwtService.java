package com.ensp.nms.security;

import com.ensp.nms.entity.Permission;
import com.ensp.nms.entity.Role;
import com.ensp.nms.entity.User;
import com.ensp.nms.service.AuthorityVersionService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;
    private final AuthorityVersionService authorityVersionService;

    public JwtService(
            @Value("${auth.jwt.secret}") String secret,
            @Value("${auth.jwt.expiration-ms:86400000}") long expirationMs,
            AuthorityVersionService authorityVersionService
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
        this.authorityVersionService = authorityVersionService;
    }

    public String generateToken(User user) {
        List<String> roles = user.getRoles() == null
                ? List.of()
                : user.getRoles().stream().map(Role::getName).collect(Collectors.toList());
        List<String> permissions = collectPermissions(user);

        Date now = new Date();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("roles", roles)
                .claim("permissions", permissions)
                .claim("realName", user.getRealName())
                .claim("authorityVersion", authorityVersionService.current())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    public long getAuthorityVersion(String token) {
        Object v = parseClaims(token).get("authorityVersion");
        if (v instanceof Number n) {
            return n.longValue();
        }
        // 旧 token 无版本：视为 0，与当前版本不一致时将失效并要求重登
        return 0L;
    }

    public static List<String> collectPermissions(User user) {
        if (user.getRoles() == null) return List.of();
        Set<String> names = new LinkedHashSet<>();
        for (Role role : user.getRoles()) {
            if (role.getPermissions() == null) continue;
            for (Permission p : role.getPermissions()) {
                if (p.getName() != null && !p.getName().isBlank()) {
                    names.add(p.getName());
                }
            }
        }
        return List.copyOf(names);
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            Claims claims = parseClaims(token);
            return claims.getExpiration() != null && claims.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    public String getUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public String getRealName(String token) {
        Object v = parseClaims(token).get("realName");
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    public List<String> getRoles(String token) {
        return asStringList(parseClaims(token).get("roles"));
    }

    @SuppressWarnings("unchecked")
    public List<String> getPermissions(String token) {
        return asStringList(parseClaims(token).get("permissions"));
    }

    private List<String> asStringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toList());
        }
        return List.of();
    }
}
