package com.ensp.nms.config;

import com.ensp.nms.device.DeviceType;
import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.Role;
import com.ensp.nms.entity.User;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.RoleRepository;
import com.ensp.nms.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.HashSet;
import java.util.Set;

/**
 * 首次启动时初始化内置管理员、角色与权限基础数据。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Slf4j
@Configuration
public class DataInitializer {

    private static final String DEFAULT_PASSWORD = "Admin@123";

    @org.springframework.beans.factory.annotation.Value("${nms.bootstrap.admin-username:admin}")
    private String bootstrapAdminUsername;

    @org.springframework.beans.factory.annotation.Value("${nms.bootstrap.admin-password:}")
    private String bootstrapAdminPassword;

    @Bean
    @Order(0)
    CommandLineRunner initDatabase(
            DeviceRepository deviceRepository,
            UserRepository userRepository,
            RoleRepository roleRepository,
            BCryptPasswordEncoder passwordEncoder,
            com.ensp.nms.service.RbacSeedService rbacSeedService
    ) {
        return args -> {
            rbacSeedService.ensureRbacSeeded();

            // 部分AC设备SSH协商不稳定，统一改用telnet管理，端口同步为23
            for (Device d : deviceRepository.findByDeviceType(DeviceType.AC.getCode())) {
                boolean changed = false;
                if (!d.isTelnetManagement()) {
                    d.setManagementProtocol("telnet");
                    changed = true;
                }
                if (d.getSshPort() == null || d.getSshPort() == 22) {
                    d.setSshPort(23);
                    changed = true;
                }
                if (changed) {
                    deviceRepository.save(d);
                }
            }

            if (userRepository.count() == 0) {
                String rawPassword = bootstrapAdminPassword;
                if (rawPassword == null || rawPassword.isBlank()) {
                    rawPassword = DEFAULT_PASSWORD;
                    log.info("使用默认管理员口令，请首次登录后立即修改");
                }
                Role adminRole = roleRepository.findByName("ADMIN").orElse(null);

                User admin = new User();
                admin.setUsername(bootstrapAdminUsername);
                admin.setPassword(passwordEncoder.encode(rawPassword));
                admin.setEmail("admin@nms.local");
                admin.setRealName("系统管理员");
                admin.setMustChangePassword(true);
                if (adminRole != null) {
                    Set<Role> roles = new HashSet<>();
                    roles.add(adminRole);
                    admin.setRoles(roles);
                }
                userRepository.save(admin);
                log.info("管理员账号 {} 已初始化，首次登录须修改密码", bootstrapAdminUsername);
            }
        };
    }

}
