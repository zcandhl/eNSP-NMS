package com.ensp.nms.config;

import com.ensp.nms.entity.Device;
import com.ensp.nms.entity.DevicePort;
import com.ensp.nms.entity.Role;
import com.ensp.nms.entity.User;
import com.ensp.nms.repository.DevicePortRepository;
import com.ensp.nms.repository.DeviceRepository;
import com.ensp.nms.repository.RoleRepository;
import com.ensp.nms.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner initDatabase(
            DeviceRepository deviceRepository,
            DevicePortRepository devicePortRepository,
            UserRepository userRepository,
            RoleRepository roleRepository,
            BCryptPasswordEncoder passwordEncoder,
            com.ensp.nms.service.RbacSeedService rbacSeedService
    ) {
        return args -> {
            rbacSeedService.ensureRbacSeeded();

            if (userRepository.count() == 0) {
                Role adminRole = roleRepository.findByName("ADMIN").orElse(null);
                Role operatorRole = roleRepository.findByName("OPERATOR").orElse(null);
                
                User admin = new User();
                admin.setUsername("admin");
                admin.setPassword(passwordEncoder.encode("Admin@123"));
                admin.setEmail("admin@ensp.local");
                admin.setRealName("系统管理员");
                admin.setMustChangePassword(true);
                if (adminRole != null) {
                    Set<Role> roles = new HashSet<>();
                    roles.add(adminRole);
                    admin.setRoles(roles);
                }
                
                User operator = new User();
                operator.setUsername("operator");
                operator.setPassword(passwordEncoder.encode("Operator@123"));
                operator.setEmail("operator@ensp.local");
                operator.setRealName("运维操作员");
                operator.setMustChangePassword(true);
                if (operatorRole != null) {
                    Set<Role> roles = new HashSet<>();
                    roles.add(operatorRole);
                    operator.setRoles(roles);
                }
                
                userRepository.save(admin);
                userRepository.save(operator);
            }

            // 已有库：默认口令仍为 Admin@123 的账号，补强制改密标记
            userRepository.findByUsername("admin").ifPresent(u -> {
                if (passwordEncoder.matches("Admin@123", u.getPassword())
                        && !Boolean.TRUE.equals(u.getMustChangePassword())) {
                    u.setMustChangePassword(true);
                    userRepository.save(u);
                }
            });
            userRepository.findByUsername("operator").ifPresent(u -> {
                if (passwordEncoder.matches("Operator@123", u.getPassword())
                        && !Boolean.TRUE.equals(u.getMustChangePassword())) {
                    u.setMustChangePassword(true);
                    userRepository.save(u);
                }
            });

            if (deviceRepository.count() == 0) {
                Device d1 = new Device();
                d1.setName("AR2220-路由器-01");
                d1.setIpAddress("192.168.56.10");
                d1.setModel("AR2220");
                d1.setVendor("Huawei");
                d1.setSnmpVersion("v2c");
                d1.setSnmpCommunity("public");
                d1.setSnmpPort(161);
                d1.setSshUsername("admin");
                d1.setSshPassword("Admin@123456");
                d1.setSshPort(22);
                d1.setStatus("online");
                d1.setDescription("eNSP 模拟路由器");
                d1.setLastSeen(LocalDateTime.now());
                d1 = deviceRepository.save(d1);

                Device d2 = new Device();
                d2.setName("S5700-交换机-01");
                d2.setIpAddress("192.168.56.11");
                d2.setModel("S5700");
                d2.setVendor("Huawei");
                d2.setSnmpVersion("v2c");
                d2.setSnmpCommunity("public");
                d2.setSnmpPort(161);
                d2.setStatus("online");
                d2.setDescription("eNSP 模拟交换机");
                d2.setLastSeen(LocalDateTime.now());
                d2 = deviceRepository.save(d2);

                Device d3 = new Device();
                d3.setName("USG6000-防火墙-01");
                d3.setIpAddress("192.168.56.12");
                d3.setModel("USG6000");
                d3.setVendor("Huawei");
                d3.setSnmpVersion("v2c");
                d3.setSnmpCommunity("public");
                d3.setSnmpPort(161);
                d3.setStatus("offline");
                d3.setDescription("eNSP 模拟防火墙");
                d3 = deviceRepository.save(d3);

                List<DevicePort> d1Ports = new ArrayList<>();
                d1Ports.add(createPort(d1.getId(), "GigabitEthernet0/0/0", "ethernet", 1, "up", "up", 1000000000L));
                d1Ports.add(createPort(d1.getId(), "GigabitEthernet0/0/1", "ethernet", 2, "up", "up", 1000000000L));
                d1Ports.add(createPort(d1.getId(), "GigabitEthernet0/0/2", "ethernet", 3, "up", "down", 1000000000L));
                d1Ports.add(createPort(d1.getId(), "Serial0/0/0", "serial", 4, "up", "up", 2000000L));
                d1Ports.add(createPort(d1.getId(), "Serial0/0/1", "serial", 5, "down", "down", 2000000L));
                devicePortRepository.saveAll(d1Ports);

                List<DevicePort> d2Ports = new ArrayList<>();
                d2Ports.add(createPort(d2.getId(), "Ethernet0/0/1", "ethernet", 1, "up", "up", 100000000L));
                d2Ports.add(createPort(d2.getId(), "Ethernet0/0/2", "ethernet", 2, "up", "up", 100000000L));
                d2Ports.add(createPort(d2.getId(), "Ethernet0/0/3", "ethernet", 3, "up", "up", 100000000L));
                d2Ports.add(createPort(d2.getId(), "Ethernet0/0/4", "ethernet", 4, "up", "down", 100000000L));
                d2Ports.add(createPort(d2.getId(), "Ethernet0/0/5", "ethernet", 5, "up", "up", 100000000L));
                d2Ports.add(createPort(d2.getId(), "Ethernet0/0/6", "ethernet", 6, "up", "up", 100000000L));
                d2Ports.add(createPort(d2.getId(), "Ethernet0/0/7", "ethernet", 7, "up", "up", 100000000L));
                d2Ports.add(createPort(d2.getId(), "Ethernet0/0/8", "ethernet", 8, "up", "up", 100000000L));
                devicePortRepository.saveAll(d2Ports);

                List<DevicePort> d3Ports = new ArrayList<>();
                d3Ports.add(createPort(d3.getId(), "GigabitEthernet0/0/0", "ethernet", 1, "up", "down", 1000000000L));
                d3Ports.add(createPort(d3.getId(), "GigabitEthernet0/0/1", "ethernet", 2, "up", "down", 1000000000L));
                d3Ports.add(createPort(d3.getId(), "GigabitEthernet0/0/2", "ethernet", 3, "up", "down", 1000000000L));
                devicePortRepository.saveAll(d3Ports);
            }

            System.out.println("=== 测试数据已初始化 ===");
            System.out.println("默认账号: admin / Admin@123");
        };
    }

    private DevicePort createPort(Long deviceId, String portName, String portType, 
                                   Integer ifIndex, String adminStatus, String operStatus, Long speed) {
        DevicePort port = new DevicePort();
        port.setDeviceId(deviceId);
        port.setPortName(portName);
        port.setPortType(portType);
        port.setIfIndex(ifIndex);
        port.setAdminStatus(adminStatus);
        port.setOperStatus(operStatus);
        port.setSpeed(speed);
        port.setMtu(1500);
        port.setDescription(portName + " 接口");
        return port;
    }
}
