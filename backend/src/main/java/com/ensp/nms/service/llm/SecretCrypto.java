package com.ensp.nms.service.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 使用 JWT secret 派生 AES-GCM 密钥，加密存储 LLM API Key。
 */
@Component
public class SecretCrypto {

    private static final String PREFIX = "enc:v1:";
    private final byte[] keyBytes;
    private final SecureRandom random = new SecureRandom();

    public SecretCrypto(@Value("${auth.jwt.secret}") String jwtSecret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            this.keyBytes = sha.digest(jwtSecret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("初始化密钥失败", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + cipherText.length);
            buf.put(iv);
            buf.put(cipherText);
            return PREFIX + Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    public String decrypt(String enc) {
        if (enc == null || enc.isBlank()) {
            return null;
        }
        if (!enc.startsWith(PREFIX)) {
            // 兼容明文历史
            return enc;
        }
        try {
            byte[] all = Base64.getDecoder().decode(enc.substring(PREFIX.length()));
            byte[] iv = new byte[12];
            byte[] cipherText = new byte[all.length - 12];
            System.arraycopy(all, 0, iv, 0, 12);
            System.arraycopy(all, 12, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败", e);
        }
    }

    public boolean isMasked(String value) {
        return value != null && (value.contains("****") || value.matches("\\*+"));
    }
}
