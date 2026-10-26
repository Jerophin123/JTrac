package com.jtac.integration.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Encrypts secrets at rest with AES-256-GCM. Ciphertext format: {@code v1:} + base64 of
 * 12-byte nonce followed by ciphertext and tag. The associated data (e.g. the owning row's
 * id) binds a ciphertext to its row, so it cannot be copied to another one.
 */
@Component
class SecretBox {

    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);
    private static final String PREFIX = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    SecretBox(IntegrationProperties properties) {
        this.key = new SecretKeySpec(loadKey(properties), "AES");
    }

    String encrypt(String plaintext, String associatedData) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    String decrypt(String ciphertext, String associatedData) {
        if (ciphertext == null || !ciphertext.startsWith(PREFIX)) {
            throw new IllegalStateException("Unsupported ciphertext format");
        }
        try {
            byte[] in = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 0, NONCE_BYTES));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Secret could not be decrypted (wrong JTRAC_SECRET_KEY / JTAC_SECRET_KEY?)", e);
        }
    }

    private static byte[] loadKey(IntegrationProperties properties) {
        if (properties.secretKey() != null && !properties.secretKey().isBlank()) {
            return decodeKey(properties.secretKey().strip(), "jtac.integrations.secret-key");
        }
        if (properties.requireSecretKey()) {
            throw new IllegalStateException("jtac.integrations.secret-key (JTRAC_SECRET_KEY / JTAC_SECRET_KEY) must be set: "
                    + "a base64-encoded 32-byte key, e.g. from `openssl rand -base64 32`.");
        }
        Path file = properties.secretKeyFile();
        try {
            if (Files.exists(file)) {
                return decodeKey(Files.readString(file).strip(), file.toString());
            }
            byte[] generated = new byte[32];
            RANDOM.nextBytes(generated);
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, Base64.getEncoder().encodeToString(generated), StandardOpenOption.CREATE_NEW);
            PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            }
            log.warn("Generated a development encryption key at {}. Set JTRAC_SECRET_KEY in production.", file);
            return generated;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read or create the secret key file " + file, e);
        }
    }

    private static byte[] decodeKey(String encoded, String source) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(source + " is not valid base64", e);
        }
        if (key.length != 32) {
            throw new IllegalStateException(source + " must decode to exactly 32 bytes");
        }
        return key;
    }
}
