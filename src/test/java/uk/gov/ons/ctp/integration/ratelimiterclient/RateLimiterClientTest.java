package uk.gov.ons.ctp.integration.ratelimiterclient;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.ons.ctp.common.FixtureHelper;
import uk.gov.ons.ctp.common.domain.CaseType;
import uk.gov.ons.ctp.common.domain.UniquePropertyReferenceNumber;
import uk.gov.ons.ctp.common.error.CTPException;
import uk.gov.ons.ctp.common.error.CTPException.Fault;
import uk.gov.ons.ctp.common.rest.RestClient;
import uk.gov.ons.ctp.integration.common.product.model.Product;
import uk.gov.ons.ctp.integration.common.product.model.Product.DeliveryChannel;
import uk.gov.ons.ctp.integration.common.product.model.Product.ProductGroup;
import uk.gov.ons.ctp.integration.ratelimiter.client.RateLimiterClient;
import uk.gov.ons.ctp.integration.ratelimiter.client.RateLimiterClient.Domain;
import uk.gov.ons.ctp.integration.ratelimiter.model.DescriptorEntry;
import uk.gov.ons.ctp.integration.ratelimiter.model.LimitDescriptor;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitRequest;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitResponse;

/**
 * This class contains unit tests for the CaseServiceClientServiceImpl class. It mocks out the Rest
 * calls and returns dummy responses to represent what would be returned by the case service.
 */
public class RateLimiterClientTest {

  @Mock RestClient restClient;

  @InjectMocks RateLimiterClient rateLimiterClient = new RateLimiterClient(restClient);

  private Product product =
      new Product(
          "P1",
          ProductGroup.QUESTIONNAIRE,
          "Large print Welsh",
          null,
          true,
          null,
          DeliveryChannel.SMS,
          null,
          null,
          null);

  private Domain domain = RateLimiterClient.Domain.RH;
  private UniquePropertyReferenceNumber uprn = new UniquePropertyReferenceNumber("24234234");
  private CaseType caseType = CaseType.HH;
  private String ipAddress = "123.123.123.123";

  @Before
  public void initMocks() {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void checkRateLimit_belowThreshold_withTelNo() throws CTPException {
    doCheckRateLimit_belowThreshold(true);
  }

  @Test
  public void checkRateLimit_belowThreshold_withoutTelNo() throws CTPException {
    doCheckRateLimit_belowThreshold(false);
  }

  @Test
  public void checkRateLimit_aboveThreshold() throws Exception {
    // Limit request is going to fail with exception. This needs to contain a string with the
    // limiters
    // too-many-requests response
    RateLimitResponse tooManyRequestsDTO =
        FixtureHelper.loadClassFixtures(RateLimitResponse[].class).get(0);
    String tooManyRequestsString = new ObjectMapper().writeValueAsString(tooManyRequestsDTO);
    ResponseStatusException failureException =
        new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, tooManyRequestsString, null);
    Mockito.when(restClient.postResource(eq("/json"), any(), eq(RateLimitResponse.class), eq("")))
        .thenThrow(failureException);

    // Confirm that limiter request fails with a 429 exception
    try {
      rateLimiterClient.checkRateLimit(domain, product, caseType, ipAddress, uprn, "0171 3434");
      fail();
    } catch (ResponseStatusException e) {
      assertEquals(failureException, e);
      assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
    }
  }

  @Test
  public void checkRateLimit_limiterOtherError() throws Exception {
    // Limit request is going to fail with exception that simulates an unexpected error from the
    // limiter. ie, http
    // response status is neither an expected 200 or 429
    RateLimitResponse repsonseDTO =
        FixtureHelper.loadClassFixtures(RateLimitResponse[].class).get(0);
    String tooManyRequestsString = new ObjectMapper().writeValueAsString(repsonseDTO);
    ResponseStatusException failureException =
        new ResponseStatusException(HttpStatus.BAD_REQUEST, tooManyRequestsString, null);
    Mockito.when(restClient.postResource(eq("/json"), any(), eq(RateLimitResponse.class), eq("")))
        .thenThrow(failureException);

    // Confirm that limiter request fails with a CTPException
    try {
      rateLimiterClient.checkRateLimit(domain, product, caseType, ipAddress, uprn, null);
      fail();
    } catch (CTPException e) {
      assertEquals(failureException, e.getCause());
      assertEquals(Fault.SYSTEM_ERROR, e.getFault());
    }
  }

  @Test
  public void checkRateLimit_corruptedLimiterJson() throws Exception {
    // This test simulates an internal error in which the call to the limiter has responded with a
    // 429 but
    // the response JSon has somehow been corrupted
    String corruptedJson = "aoeu<.p#$%^EOUAEOU3245";
    ResponseStatusException failureException =
        new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, corruptedJson, null);
    Mockito.when(restClient.postResource(eq("/json"), any(), eq(RateLimitResponse.class), eq("")))
        .thenThrow(failureException);

    // Confirm that limiter request fails with a CTPException
    try {
      rateLimiterClient.checkRateLimit(domain, product, caseType, ipAddress, uprn, null);
      fail();
    } catch (CTPException e) {
      assertTrue(e.getMessage().contains("Failed to parse"));
      assertEquals(Fault.SYSTEM_ERROR, e.getFault());
    }
  }

  private void doCheckRateLimit_belowThreshold(boolean useTelNo) throws CTPException {

    RateLimitResponse fakeResponse = new RateLimitResponse();
    Mockito.when(restClient.postResource(eq("/json"), any(), eq(RateLimitResponse.class), eq("")))
        .thenReturn(fakeResponse);

    String telNo = useTelNo ? "0123 3434333" : null;
    RateLimitResponse response =
        rateLimiterClient.checkRateLimit(domain, product, caseType, ipAddress, uprn, telNo);
    assertEquals(fakeResponse, response);

    // Grab the request sent to the limiter
    ArgumentCaptor<RateLimitRequest> limitRequestCaptor =
        ArgumentCaptor.forClass(RateLimitRequest.class);
    Mockito.verify(restClient).postResource(any(), limitRequestCaptor.capture(), any(), any());
    RateLimitRequest request = limitRequestCaptor.getValue();

    // Verify that the limit request is correct
    int expectedNumDescriptors = useTelNo ? 3 : 2;
    assertEquals(expectedNumDescriptors, request.getDescriptors().size());
    verifyDescriptor(request, 0, product, caseType, "ipAddress", ipAddress);
    verifyDescriptor(request, 1, product, caseType, "uprn", Long.toString(uprn.getValue()));
    if (useTelNo) {
      verifyDescriptor(request, 2, product, caseType, "telNo", telNo);
    }
  }

  private void verifyDescriptor(
      RateLimitRequest request,
      int index,
      Product product,
      CaseType caseType,
      String finalKeyName,
      String finalKeyValue) {
    LimitDescriptor descriptor = request.getDescriptors().get(index);
    assertEquals(5, descriptor.getEntries().size());
    verifyEntry(descriptor, 0, "productGroup", product.getProductGroup().name());
    verifyEntry(descriptor, 1, "individual", Boolean.toString(product.getIndividual()));
    verifyEntry(descriptor, 2, "deliveryChannel", product.getDeliveryChannel().name());
    verifyEntry(descriptor, 3, "caseType", caseType.name());
    verifyEntry(descriptor, 4, finalKeyName, finalKeyValue);
  }

  private void verifyEntry(
      LimitDescriptor descriptor, int index, String expectedKey, String expectedValue) {
    DescriptorEntry entry = descriptor.getEntries().get(index);
    assertEquals(expectedKey, entry.getKey());
    assertEquals(expectedValue, entry.getValue());
  }
}
