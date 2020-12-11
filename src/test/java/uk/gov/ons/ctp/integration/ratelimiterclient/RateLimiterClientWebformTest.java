package uk.gov.ons.ctp.integration.ratelimiterclient;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.ons.ctp.common.FixtureHelper;
import uk.gov.ons.ctp.common.error.CTPException;
import uk.gov.ons.ctp.common.error.CTPException.Fault;
import uk.gov.ons.ctp.common.rest.RestClient;
import uk.gov.ons.ctp.integration.ratelimiter.client.RateLimiterClient;
import uk.gov.ons.ctp.integration.ratelimiter.client.RateLimiterClient.Domain;
import uk.gov.ons.ctp.integration.ratelimiter.model.DescriptorEntry;
import uk.gov.ons.ctp.integration.ratelimiter.model.LimitDescriptor;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitRequest;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitResponse;

/** This class contains unit tests for limit testing Webform requests. */
@RunWith(MockitoJUnitRunner.class)
public class RateLimiterClientWebformTest {

  @Mock RestClient restClient;

  @InjectMocks RateLimiterClient rateLimiterClient = new RateLimiterClient(restClient);

  private Domain domain = RateLimiterClient.Domain.RH;

  @Test
  public void checkWebformRateLimit_nullDomain() {
    CTPException exception =
        assertThrows(
            CTPException.class,
            () -> {
              rateLimiterClient.checkWebformRateLimit(null, "100.101.88.99");
            });
    assertTrue(exception.getMessage(), exception.getMessage().contains("'domain' cannot be null"));
  }

  @Test
  public void checkWebformRateLimit_nullClientIP() throws ResponseStatusException, CTPException {
    RateLimitResponse response = rateLimiterClient.checkWebformRateLimit(domain, null);
    assertNull(response);
  }

  @Test
  public void checkWebformRateLimit_emptyClientIP() {
    CTPException exception =
        assertThrows(
            CTPException.class,
            () -> {
              rateLimiterClient.checkWebformRateLimit(domain, "");
            });
    assertTrue(exception.getMessage(), exception.getMessage().contains("cannot be blank"));
  }

  @Test
  public void checkWebformRateLimit_belowThreshold() throws CTPException {
    // Rate limiter is going to be happy with limit request
    RateLimitResponse fakeResponse = new RateLimitResponse();
    Mockito.when(restClient.postResource(eq("/json"), any(), eq(RateLimitResponse.class), eq("")))
        .thenReturn(fakeResponse);

    // Run test
    String ipAddress = "123.123.123.123";
    RateLimitResponse response = rateLimiterClient.checkWebformRateLimit(domain, ipAddress);
    assertEquals(fakeResponse, response);

    // Grab the request sent to the limiter
    ArgumentCaptor<RateLimitRequest> limitRequestCaptor =
        ArgumentCaptor.forClass(RateLimitRequest.class);
    Mockito.verify(restClient).postResource(any(), limitRequestCaptor.capture(), any(), any());
    RateLimitRequest request = limitRequestCaptor.getValue();

    // Verify that the limit request contains a ipAddress based descriptor
    assertEquals(1, request.getDescriptors().size());
    verifyDescriptor(request, 0, "ipAddress", ipAddress);
  }

  @Test
  public void checkWebformRateLimit_aboveThreshold() throws Exception {
    // Limit request is going to fail with exception. This needs to contain a string with the
    // limiters too-many-requests response
    RateLimitResponse tooManyRequestsDTO =
        FixtureHelper.loadClassFixtures(RateLimitResponse[].class).get(0);
    String tooManyRequestsString = new ObjectMapper().writeValueAsString(tooManyRequestsDTO);
    ResponseStatusException failureException =
        new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, tooManyRequestsString, null);
    Mockito.when(restClient.postResource(eq("/json"), any(), eq(RateLimitResponse.class), eq("")))
        .thenThrow(failureException);

    // Confirm that limiter request fails with a 429 exception
    try {
      rateLimiterClient.checkWebformRateLimit(domain, "123.111.222.23");
      fail();
    } catch (ResponseStatusException e) {
      assertEquals(failureException, e);
      assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
    }
  }

  @Test
  public void checkWebformRateLimit_limiterOtherError() throws Exception {
    // Limit request is going to fail with exception that simulates an unexpected error from the
    // limiter. ie, http response status is neither an expected 200 or 429
    RateLimitResponse repsonseDTO =
        FixtureHelper.loadClassFixtures(RateLimitResponse[].class).get(0);
    String tooManyRequestsString = new ObjectMapper().writeValueAsString(repsonseDTO);
    ResponseStatusException failureException =
        new ResponseStatusException(HttpStatus.BAD_REQUEST, tooManyRequestsString, null);
    Mockito.when(restClient.postResource(eq("/json"), any(), eq(RateLimitResponse.class), eq("")))
        .thenThrow(failureException);

    // Confirm that limiter request fails with expected CTPException
    try {
      rateLimiterClient.checkWebformRateLimit(domain, "11.134.234.64");
      fail();
    } catch (CTPException e) {
      assertEquals(failureException, e.getCause());
      assertEquals(Fault.SYSTEM_ERROR, e.getFault());
    }
  }

  @Test
  public void checkWebformRateLimit_corruptedLimiterJson() throws Exception {
    // This test simulates an internal error in which the call to the limiter has responded
    // with a 429 but the response JSon has somehow been corrupted
    String corruptedJson = "aoeu<.p#$%^EOUAEOU3245";
    ResponseStatusException failureException =
        new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, corruptedJson, null);
    Mockito.when(restClient.postResource(eq("/json"), any(), eq(RateLimitResponse.class), eq("")))
        .thenThrow(failureException);

    // Confirm that limiter request fails with a CTPException
    try {
      rateLimiterClient.checkWebformRateLimit(domain, "11.134.234.64");
      fail();
    } catch (CTPException e) {
      assertTrue(e.getMessage().contains("Failed to parse"));
      assertEquals(Fault.SYSTEM_ERROR, e.getFault());
    }
  }

  private void verifyDescriptor(
      RateLimitRequest request, int index, String finalKeyName, String finalKeyValue) {
    LimitDescriptor descriptor = request.getDescriptors().get(index);
    assertEquals(2, descriptor.getEntries().size());
    verifyEntry(descriptor, 0, "request", "WEBFORM");
    verifyEntry(descriptor, 1, finalKeyName, finalKeyValue);
  }

  private void verifyEntry(
      LimitDescriptor descriptor, int index, String expectedKey, String expectedValue) {
    DescriptorEntry entry = descriptor.getEntries().get(index);
    assertEquals(expectedKey, entry.getKey());
    assertEquals(expectedValue, entry.getValue());
  }
}
