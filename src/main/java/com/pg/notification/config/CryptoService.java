package com.pg.notification.config;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM encryption for per-PG secrets (WhatsApp access token).
 * Key is derived from app.crypto.secret.
 * Format: base64( iv[12] || ciphertext+tag ).
 */
@Service
public class CryptoService {

	private static final int IV_LENGTH = 12;
	private static final int TAG_BITS = 128;

	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public CryptoService(@Value("${app.crypto.secret:mypg-default-secret-encryption-key-32chars}") String secret)
			throws GeneralSecurityException {
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
		this.key = new SecretKeySpec(hash, "AES");
	}

	public String encrypt(String plain) {
		if (plain == null) {
			return null;
		}
		try {
			byte[] iv = new byte[IV_LENGTH];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

			byte[] out = new byte[IV_LENGTH + enc.length];
			System.arraycopy(iv, 0, out, 0, IV_LENGTH);
			System.arraycopy(enc, 0, out, IV_LENGTH, enc.length);
			return Base64.getEncoder().encodeToString(out);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Encryption failed", e);
		}
	}

	public String decrypt(String encrypted) {
		if (encrypted == null) {
			return null;
		}
		try {
			byte[] in = Base64.getDecoder().decode(encrypted);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_LENGTH));
			return new String(cipher.doFinal(in, IV_LENGTH, in.length - IV_LENGTH), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("Decryption failed (corrupted value or invalid secret)", e);
		}
	}
}
