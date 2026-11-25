package com.ensp.nms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SNMP Trap接收配置：端口与SNMPv3 USM用户。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15 */

@Data
@Component
@ConfigurationProperties(prefix = "snmp.trap")
public class SnmpTrapProperties {

    private int port = 162;

    private V3 v3 = new V3();

    @Data
    public static class V3 {
        private boolean enabled = true;

        private List<User> users = new ArrayList<>();
    }

    @Data
    public static class User {
        private String username;
        private String authProtocol = "SHA";
        private String authPassword = "";
        private String privProtocol = "AES128";
        private String privPassword = "";
    }
}
