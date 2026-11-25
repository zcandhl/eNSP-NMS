package com.ensp.nms.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局权限版本：角色权限变更时递增，用于使旧JWT失效。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

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
