package uk.gov.ons.ctp.integration.ratelimiter.util;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.Test;

public class EncryptorTest {
  //
  // some samples generated from:
  // echo -n "$VALUE" | openssl enc -aes-256-cbc -pass "pass:$PASSWORD" | base64
  //
  private static final String ENCRYPTED_1 = "U2FsdGVkX1/BLHViDnYcmmf+6W0JCcISt3SttPVP1lM=";
  private static final String ENCRYPTED_2 = "U2FsdGVkX18PgyWlpU+4mKZoUDP0cp6mFOuALsZhO6Q=";
  private static final String ENCRYPTED_3 = "U2FsdGVkX19GWj9AYD4rhNslbV8WMBrrLq2NHIb/oL0=";

  private void verifyRoundTripEncrypt(String password, String value) throws Exception {
    String encrypted = Encryptor.aesEncrypt(password, value);
    assertEquals(value, Encryptor.decrypt(password, encrypted));
  }

  @Test
  public void shouldEncrypt() throws Exception {
    verifyRoundTripEncrypt("password", "123");
    verifyRoundTripEncrypt("another-password", "0798 356 789");
    verifyRoundTripEncrypt("yFk6_]&FBDy,eeYK", "0798 356 789");
  }

  @Test
  public void shouldBeCompatibleFormat() throws Exception {
    assertTrue(Encryptor.isValidEncryptedFormat(ENCRYPTED_1));
    assertTrue(Encryptor.isValidEncryptedFormat(ENCRYPTED_2));
    assertTrue(Encryptor.isValidEncryptedFormat(ENCRYPTED_3));
  }

  @Test
  public void shouldDecrypt() throws Exception {
    assertEquals("rob", Encryptor.decrypt("password", ENCRYPTED_1));
    assertEquals("0765 345 987", Encryptor.decrypt("Resp0ndentH0me", ENCRYPTED_2));
    assertEquals("02380263345", Encryptor.decrypt("crazy777", ENCRYPTED_3));
  }

  @Test(expected = Exception.class)
  public void shouldRejectDecryptionWithWrongPassword() throws Exception {
    Encryptor.decrypt("password8", ENCRYPTED_1);
  }

  @Test(expected = Exception.class)
  public void shouldRejectDecryptionWithBadSource() throws Exception {
    Encryptor.decrypt("password", "BadEncryptionString");
  }

  @Test(expected = RuntimeException.class)
  public void shouldRejectEncryptionWithNullPassword() throws Exception {
    Encryptor.aesEncrypt(null, "123");
  }
}
