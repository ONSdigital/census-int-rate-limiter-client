package uk.gov.ons.ctp.integration.ratelimiterclient;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.Test;
import uk.gov.ons.ctp.integration.ratelimiter.client.Encryptor;

public class EncryptorTest {

  @Test
  public void dummy() {}

  @Test
  public void shouldEncrypt() throws Exception {
    String encrypted = Encryptor.aesEncrypt("123", "password");
    System.out.println(encrypted);
    String decrypted = Encryptor.decrypt("password", encrypted);
    assertEquals("123", decrypted);
  }

  // WRITEME
}
