package com.ensp.nms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SNMP Trap 接收配置：端口 + SNMPv3 USM 用户（v1/v2c 无需用户即可解码）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "snmp.trap")
public class SnmpTrapProperties {

    /** UDP 监听端口，默认 162 */
    private int port = 162;

    private V3 v3 = new V3();

    @Data
    public static class V3 {
        /** 是否注册 MPv3/USM；关闭则仅收 v1/v2c */
        private boolean enabled = true;

        /**
         * Trap/Inform 对端 USM 用户列表。
         * 设备侧 snmp-agent usm-user / target-host v3 需与此一致，否则 v3 报文无法解密。
         */
        private List<User> users = new ArrayList<>();
    }

    @Data
    public static class User {
        private String username;
        /** none | MD5 | SHA | SHA256 | SHA512 */
        private String authProtocol = "SHA";
        private String authPassword = "";
        /** none | DES | AES128 | AES192 | AES256 */
        private String privProtocol = "AES128";
        private String privPassword = "";
    }
}
