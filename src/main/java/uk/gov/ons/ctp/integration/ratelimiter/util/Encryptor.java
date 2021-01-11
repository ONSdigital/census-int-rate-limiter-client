package uk.gov.ons.ctp.integration.ratelimiter.util;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mimics the OpenSSL AES Cipher options for encrypting and decrypting messages.
 *
 * <p>Adapted from:
 * https://stackoverflow.com/questions/32508961/java-equivalent-of-an-openssl-aes-cbc-encryption
 */
public final class Encryptor {
  /** OpenSSL's magic initial bytes. */
  private static final String SALTED_STR = "Salted__";

  private static final int GENERATED_SALT_LENGTH = 8;
  private static final int FULL_SALT_LENGTH = GENERATED_SALT_LENGTH + SALTED_STR.length();
  private static final byte[] SALTED_MAGIC = SALTED_STR.getBytes(US_ASCII);

  private Encryptor() {}

  public static String aesEncrypt(String password, String value) {
    try {
      return encrypt(password, value);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String encrypt(String password, String clearText)
      throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
          InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {

    final byte[] salt = (new SecureRandom()).generateSeed(GENERATED_SALT_LENGTH);
    byte[] keyAndIv = makeKeyAndIv(password, salt);
    final Cipher cipher = createCipher(Cipher.ENCRYPT_MODE, keyAndIv);
    byte[] data = cipher.doFinal(clearText.getBytes(UTF_8));
    data = array_concat(array_concat(SALTED_MAGIC, salt), data);
    return Base64.getEncoder().encodeToString(data);
  }

  static String decrypt(String password, String source)
      throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
          InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
    if (!isValidEncryptedFormat(source)) {
      throw new IllegalArgumentException(
          "Initial bytes from input do not match OpenSSL SALTED_MAGIC salt value.");
    }

    final byte[] inBytes = Base64.getDecoder().decode(source);
    final byte[] salt = Arrays.copyOfRange(inBytes, SALTED_MAGIC.length, FULL_SALT_LENGTH);
    byte[] keyAndIv = makeKeyAndIv(password, salt);
    final Cipher cipher = createCipher(Cipher.DECRYPT_MODE, keyAndIv);
    final byte[] clear =
        cipher.doFinal(inBytes, FULL_SALT_LENGTH, inBytes.length - FULL_SALT_LENGTH);
    return new String(clear, UTF_8);
  }

  private static Cipher createCipher(int mode, byte[] keyAndIv)
      throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
          InvalidAlgorithmParameterException {
    final byte[] keyValue = Arrays.copyOfRange(keyAndIv, 0, 32);
    final byte[] iv = Arrays.copyOfRange(keyAndIv, 32, 48);
    final SecretKeySpec key = new SecretKeySpec(keyValue, "AES");
    final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(mode, key, new IvParameterSpec(iv));
    return cipher;
  }

  private static byte[] makeKeyAndIv(String password, byte[] salt) throws NoSuchAlgorithmException {
    final byte[] pass = password.getBytes(US_ASCII);
    final byte[] passAndSalt = array_concat(pass, salt);
    byte[] hash = new byte[0];
    byte[] keyAndIv = new byte[0];
    for (int i = 0; i < 3 && keyAndIv.length < 48; i++) {
      final byte[] hashData = array_concat(hash, passAndSalt);
      final MessageDigest md = MessageDigest.getInstance("MD5");
      hash = md.digest(hashData);
      keyAndIv = array_concat(keyAndIv, hash);
    }
    return keyAndIv;
  }

  static boolean isValidEncryptedFormat(String source) {
    final byte[] inBytes = Base64.getDecoder().decode(source);
    final byte[] shouldBeMagic = Arrays.copyOfRange(inBytes, 0, SALTED_MAGIC.length);
    return Arrays.equals(shouldBeMagic, SALTED_MAGIC);
  }

  private static byte[] array_concat(final byte[] a, final byte[] b) {
    final byte[] c = new byte[a.length + b.length];
    System.arraycopy(a, 0, c, 0, a.length);
    System.arraycopy(b, 0, c, a.length, b.length);
    return c;
  }
}
