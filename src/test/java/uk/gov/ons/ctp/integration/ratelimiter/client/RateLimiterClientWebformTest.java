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

/** This class contains unit tests for limit testing Webform requests. */
@RunWith(MockitoJUnitRunner.class)
public class RateLimiterClientWebformTest extends RateLimiterClientTestBase {

  @Test
  public void checkWebformRateLimit_nullDomain() {
    CTPException exception =
        assertThrows(
            CTPException.class,
            () -> rateLimiterClient.checkWebformRateLimit(null, AN_IPv4_ADDRESS));
    assertTrue(exception.getMessage(), exception.getMessage().contains("'domain' cannot be null"));
  }

  @Test
  public void shouldQuietlyAcceptNullClientIpNotCallingLimiter() throws Exception {
    rateLimiterClient.checkWebformRateLimit(domain, null);
    verifyEnvoyLimiterNotCalled();
  }

  @Test
  public void shouldQuietlyAcceptEmptyClientIpNotCallingLimiter() throws Exception {
    rateLimiterClient.checkWebformRateLimit(domain, "");
    verifyEnvoyLimiterNotCalled();
  }

  @Test
  public void shouldQuietlyAcceptBadlyFormattedClientIpNotCallingLimiter() throws Exception {
    rateLimiterClient.checkWebformRateLimit(domain, "badlyformattedIpAddress");
    verifyEnvoyLimiterNotCalled();
  }

  @Test
  public void shouldQuietlyAcceptIpV6ClientIpNotCallingLimiter() throws Exception {
    rateLimiterClient.checkWebformRateLimit(domain, "2001:DB8::21f:5bff:febf:ce22:8a2e");
    verifyEnvoyLimiterNotCalled();
  }

  @Test
  public void checkWebformRateLimit_belowThreshold() throws CTPException {
    // Don't need to mock the call to restClient.postResource() as default is treated as being below
    // the limit

    // Run test
    rateLimiterClient.checkWebformRateLimit(domain, AN_IPv4_ADDRESS);

    // Grab the request sent to the limiter
    RateLimitRequest request = verifiedRequestSentToLimiter();

    // Verify that the limit request contains a ipAddress based descriptor
    assertEquals(1, request.getDescriptors().size());
    verifyDescriptor(request, 0, "ipAddress", AN_IPv4_ADDRESS);
  }

  @Test
  public void checkWebformRateLimit_aboveThreshold() throws Exception {
    // Limit request is going to fail with exception. This needs to contain a string with the
    // limiters too-many-requests response
    ResponseStatusException failureException = overTheLimitException();
    mockRateLimitException(failureException);

    // Confirm that limiter request fails with a 429 exception
    try {
      rateLimiterClient.checkWebformRateLimit(domain, AN_IPv4_ADDRESS);
      fail();
    } catch (ResponseStatusException e) {
      assertEquals(failureException, e);
      assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
    }
    verifiedRequestSentToLimiter();
  }

  @Test
  public void checkWebformRateLimit_limiterOtherError() throws Exception {
    // Limit request is going to fail with exception that simulates an unexpected error from the
    // limiter. ie, http response status is neither an expected 200 or 429
    mockRateLimitException(badRequestException());

    // Circuit breaker spots that this isn't a TOO_MANY_REQUESTS HttpStatus failure, so
    // we log an error and allow the limit check to pass. ie, no exception thrown
    rateLimiterClient.checkWebformRateLimit(domain, AN_IPv4_ADDRESS);
    verifiedRequestSentToLimiter();
  }

  @Test
  public void checkWebformRateLimit_corruptedLimiterJson() throws Exception {
    // This test simulates an internal error in which the call to the limiter has responded
    // with a 429 but the response JSon has somehow been corrupted
    mockRateLimitException(corruptedJsonException());

    // Although the rest client call fails the circuit breaker allows the limit check to pass. ie,
    // no exception thrown
    rateLimiterClient.checkWebformRateLimit(domain, AN_IPv4_ADDRESS);
    verifiedRequestSentToLimiter();
  }

  @Test
  public void checkWebformRateLimit_worksWithCircuitBreakerOpen() throws Exception {
    // Simulate circuit breaker not calling rest client
    mockRateLimitException(circuitBreakerOpenException);

    // Limit check works without an exception
    rateLimiterClient.checkWebformRateLimit(domain, AN_IPv4_ADDRESS);
  }

  private void verifyDescriptor(
      RateLimitRequest request, int index, String finalKeyName, String finalKeyValue) {
    LimitDescriptor descriptor = request.getDescriptors().get(index);
    assertEquals(2, descriptor.getEntries().size());
    verifyEntry(descriptor, 0, "request", "WEBFORM");
    verifyEntry(descriptor, 1, finalKeyName, finalKeyValue);
  }
}
