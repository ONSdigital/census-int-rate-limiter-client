package uk.gov.ons.ctp.integration.ratelimiter.client;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.ons.ctp.common.error.CTPException;
import uk.gov.ons.ctp.integration.ratelimiter.model.LimitDescriptor;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitRequest;

@RunWith(MockitoJUnitRunner.class)
public class RateLimiterClientEqLaunchTest extends RateLimiterClientTestBase {

  @Test
  public void shouldRejectNullDomain() {
    CTPException exception =
        assertThrows(
            CTPException.class,
            () -> rateLimiterClient.checkEqLaunchLimit(null, AN_IPv4_ADDRESS, 10));
    assertTrue(exception.getMessage(), exception.getMessage().contains("'domain' cannot be null"));
    verifyEnvoyLimiterNotCalled();
  }

  @Test
  public void shouldRejectZeroLoadSheddingModulus() {
    CTPException exception =
        assertThrows(
            CTPException.class,
            () -> rateLimiterClient.checkEqLaunchLimit(domain, AN_IPv4_ADDRESS, 0));
    assertTrue(
        exception.getMessage(),
        exception.getMessage().contains("'loadSheddingModulus' cannot be zero"));
    verifyEnvoyLimiterNotCalled();
  }

  @Test
  public void shouldQuietlyAcceptNullClientIpNotCallingLimiter() throws Exception {
    rateLimiterClient.checkEqLaunchLimit(domain, null, 10);
    verifyEnvoyLimiterNotCalled();
  }

  @Test
  public void shouldQuietlyAcceptEmptyClientIpNotCallingLimiter() throws Exception {
    rateLimiterClient.checkEqLaunchLimit(domain, "", 10);
    verifyEnvoyLimiterNotCalled();
  }

  @Test
  public void shouldQuietlyAcceptBadlyFormattedClientIpNotCallingLimiter() throws Exception {
    rateLimiterClient.checkEqLaunchLimit(domain, "badlyformattedIpAddress", 10);
    verifyEnvoyLimiterNotCalled();
  }

  @Test
  public void shouldQuietlyAcceptIpV6ClientIpNotCallingLimiter() throws Exception {
    rateLimiterClient.checkEqLaunchLimit(domain, "2001:DB8::21f:5bff:febf:ce22:8a2e", 10);
    verifyEnvoyLimiterNotCalled();
  }

  @Test
  public void shouldAcceptRateLimitBelowThreshold_modulo3() throws Exception {
    doCheckAndVerifyModulo("124.125.126.123", 10, 3);
  }

  @Test
  public void shouldAcceptRateLimitBelowThreshold_modulo4() throws Exception {
    doCheckAndVerifyModulo("124.125.126.9", 5, 4);
  }

  @Test
  public void shouldAcceptRateLimitBelowThreshold_modulo0() throws Exception {
    doCheckAndVerifyModulo("124.125.126.100", 10, 0);
  }

  @Test
  public void shouldAcceptRateLimitBelowThreshold_modulo9() throws Exception {
    doCheckAndVerifyModulo("124.125.126.249", 15, 9);
  }

  @Test
  public void shouldAcceptRateLimitBelowThreshold_modulo19() throws Exception {
    doCheckAndVerifyModulo("124.125.126.249", 23, 19);
  }

  private void doCheckAndVerifyModulo(String ipAddress, int loadSheddingModulus, int expectedModulo)
      throws Exception {
    rateLimiterClient.checkEqLaunchLimit(domain, ipAddress, loadSheddingModulus);

    RateLimitRequest request = verifiedRequestSentToLimiter();

    assertEquals(1, request.getDescriptors().size());
    verifyDescriptor(request, 0, "modulo", "" + expectedModulo);
  }

  @Test
  public void shouldRateLimitAboveThreshold() throws Exception {
    // Limit request is going to fail with exception. This needs to contain a string with the
    // limiters too-many-requests response
    ResponseStatusException failureException = overTheLimitException();
    mockRateLimitException(failureException);

    // Confirm that limiter request fails with a 429 exception
    try {
      rateLimiterClient.checkEqLaunchLimit(domain, AN_IPv4_ADDRESS, 10);
      fail();
    } catch (ResponseStatusException e) {
      assertEquals(failureException, e);
      assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
    }
    verifiedRequestSentToLimiter();
  }

  @Test
  public void shouldQuietlyAcceptOtherRateLimiterError() throws Exception {
    // Limit request is going to fail with exception that simulates an unexpected error from the
    // limiter. ie, http response status is neither an expected 200 or 429
    mockRateLimitException(badRequestException());

    // Circuit breaker spots that this isn't a TOO_MANY_REQUESTS HttpStatus failure, so
    // we log an error and allow the limit check to pass. ie, no exception thrown
    rateLimiterClient.checkEqLaunchLimit(domain, AN_IPv4_ADDRESS, 10);
    verifiedRequestSentToLimiter();
  }

  @Test
  public void shouldQuietlyAcceptCorruptedJsonResponseFromRateLimiter() throws Exception {
    // This test simulates an internal error in which the call to the limiter has responded
    // with a 429 but the response JSon has somehow been corrupted
    mockRateLimitException(corruptedJsonException());

    // Although the rest client call fails the circuit breaker allows the limit check to pass. ie,
    // no exception thrown
    rateLimiterClient.checkEqLaunchLimit(domain, AN_IPv4_ADDRESS, 10);
    verifiedRequestSentToLimiter();
  }

  @Test
  public void shouldWorksWithCircuitBreakerOpen() throws Exception {
    // Simulate circuit breaker not calling rest client
    mockRateLimitException(circuitBreakerOpenException);

    // Limit check works without an exception
    rateLimiterClient.checkEqLaunchLimit(domain, AN_IPv4_ADDRESS, 10);
  }

  private void verifyDescriptor(
      RateLimitRequest request, int index, String finalKeyName, String finalKeyValue) {
    LimitDescriptor descriptor = request.getDescriptors().get(index);
    assertEquals(2, descriptor.getEntries().size());
    verifyEntry(descriptor, 0, "request", "EQLAUNCH");
    verifyEntry(descriptor, 1, finalKeyName, finalKeyValue);
  }
}
