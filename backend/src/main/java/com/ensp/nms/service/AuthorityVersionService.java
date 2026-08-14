package com.ensp.nms.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局权限版本：角色权限变更时递增，用于使旧 JWT 失效。
 */
@Service
public class AuthorityVersionService {

    private final AtomicLong version = new AtomicLong(1);

    public long current() {
        return version.get();
    }

    public long bump() {
        return version.incrementAndGet();
    }
}
