package cn.lwx.lwxaiagent.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 消息加密服务（ADR-4）：AES-256-GCM + HMAC-SHA256 透明加密。
 *
 * <p>在数据访问层（MessageChatMemory）加解密，上层零感知。
 * 主密钥从环境变量 APP_MESSAGE_KEY 读取（base64 编码 256-bit 密钥）。
 * 未设置时使用 dev 默认密钥（仅本地开发）。</p>
 */
@Slf4j
@Component
public class EncryptionService {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LEN = 12;     // 96 bits
    private static final int GCM_TAG_LEN = 128;    // 16 bytes
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String KEY_DERIVE_CTX = "msg-enc-";

    /** 主密钥（从环境变量读取，fallback dev key） */
    private final byte[] masterKey;

    /** 是否启用加密 */
    private final boolean enabled;

    /** 安全的随机数生成器 */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * @param masterKeyBase64 环境变量 APP_MESSAGE_KEY（base64 编码 256-bit 密钥）
     * @param enabled         是否启用加密（app.encryption.enabled）
     */
    public EncryptionService(
            @Value("${APP_MESSAGE_KEY:}") String masterKeyBase64,
            @Value("${app.encryption.enabled:true}") boolean enabled) {
        this.enabled = enabled;
        if (masterKeyBase64 != null && !masterKeyBase64.isBlank()) {
            this.masterKey = Base64.getDecoder().decode(masterKeyBase64);
        } else {
            // Dev 默认密钥（仅本地开发，生产环境必须通过 APP_MESSAGE_KEY 注入）
            this.masterKey = new byte[32];
            log.warn("APP_MESSAGE_KEY not set, using insecure dev default key");
        }
        log.info("EncryptionService initialized: enabled={}, keyLength={}", enabled, masterKey.length);
    }

    /**
     * 加密明文。
     *
     * @param plaintext 明文
     * @param tenantId  租户 ID（用于密钥派生，单租户期固定 "default"）
     * @return base64(IV + ciphertext + tag)；加密关闭时返回明文
     */
    public String encrypt(String plaintext, String tenantId) {
        if (!enabled || plaintext == null) return plaintext;
        try {
            byte[] key = deriveKey(tenantId);
            byte[] iv = new byte[GCM_IV_LEN];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LEN, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 输出 = IV + ciphertext（含 GCM tag）
            byte[] output = new byte[GCM_IV_LEN + ciphertext.length];
            System.arraycopy(iv, 0, output, 0, GCM_IV_LEN);
            System.arraycopy(ciphertext, 0, output, GCM_IV_LEN, ciphertext.length);

            return Base64.getEncoder().encodeToString(output);
        } catch (Exception e) {
            log.error("Encryption failed: {}", e.getMessage());
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * 解密密文。
     *
     * @param ciphertextBase64 base64(IV + ciphertext + tag)；加密关闭时直接返回
     * @param tenantId         租户 ID
     * @return 明文
     */
    public String decrypt(String ciphertextBase64, String tenantId) {
        if (!enabled || ciphertextBase64 == null) return ciphertextBase64;
        try {
            byte[] key = deriveKey(tenantId);
            byte[] input = Base64.getDecoder().decode(ciphertextBase64);

            if (input.length < GCM_IV_LEN + GCM_TAG_LEN / 8) {
                log.warn("Ciphertext too short, returning as-is");
                return ciphertextBase64;
            }

            byte[] iv = new byte[GCM_IV_LEN];
            byte[] ciphertext = new byte[input.length - GCM_IV_LEN];
            System.arraycopy(input, 0, iv, 0, GCM_IV_LEN);
            System.arraycopy(input, GCM_IV_LEN, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LEN, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Decryption failed (may be plaintext from before encryption enabled): {}", e.getMessage());
            return ciphertextBase64; // 降级：返回原文（存量明文数据兼容）
        }
    }

    /**
     * 计算 HMAC-SHA256（用于 content_hmac）。
     *
     * @param plaintext 明文
     * @return HMAC 十六进制字符串；加密关闭时返回 SHA-256 hex（兼容旧格式）
     */
    public String hmac(String plaintext) {
        if (!enabled || plaintext == null) {
            return sha256(plaintext);
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(masterKey, HMAC_ALGO));
            byte[] hmac = mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hmac);
        } catch (Exception e) {
            log.error("HMAC failed: {}", e.getMessage());
            return "";
        }
    }

    /** 派生租户密钥：HMAC-SHA256(masterKey, "msg-enc-" + tenantId) */
    private byte[] deriveKey(String tenantId) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(masterKey, HMAC_ALGO));
            return mac.doFinal((KEY_DERIVE_CTX + (tenantId != null ? tenantId : "default")).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }

    /** 兼容旧格式的 SHA-256 */
    private String sha256(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return bytesToHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}