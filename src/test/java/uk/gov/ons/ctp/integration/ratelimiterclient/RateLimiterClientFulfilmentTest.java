package uk.gov.ons.ctp.integration.ratelimiterclient;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.ons.ctp.common.FixtureHelper;
import uk.gov.ons.ctp.common.domain.CaseType;
import uk.gov.ons.ctp.common.domain.UniquePropertyReferenceNumber;
import uk.gov.ons.ctp.common.error.CTPException;
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

/** This class contains unit tests for limit testing fulfilment requests. */
@RunWith(MockitoJUnitRunner.class)
public class RateLimiterClientFulfilmentTest {

  @Mock RestClient restClient;
  @Mock private CircuitBreaker circuitBreaker;

  @InjectMocks
  RateLimiterClient rateLimiterClient = new RateLimiterClient(restClient, circuitBreaker);

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

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);

    simulateCircuitBreaker();
  }

  @Test
  public void checkFulfilmentRateLimit_nullDomain() {
    CTPException exception =
        assertThrows(
            CTPException.class,
            () -> {
              rateLimiterClient.checkFulfilmentRateLimit(
                  null, product, caseType, "100.101.88.99", uprn, "0171 3434");
            });
    assertTrue(exception.getMessage(), exception.getMessage().contains("'domain' cannot be null"));
  }

  @Test
  public void checkFulfilmentRateLimit_belowThreshold_withNeitherTelNoOrIP() throws CTPException {
    docheckFulfilmentRateLimit_belowThreshold(false, false);
  }

  @Test
  public void checkFulfilmentRateLimit_belowThreshold_withNoTelButWithIP() throws CTPException {
    docheckFulfilmentRateLimit_belowThreshold(false, true);
  }

  @Test
  public void checkFulfilmentRateLimit_belowThreshold_withTelNoAndNoIP() throws CTPException {
    docheckFulfilmentRateLimit_belowThreshold(true, false);
  }

  @Test
  public void checkFulfilmentRateLimit_belowThreshold_withBothTelNoAndIP() throws CTPException {
    docheckFulfilmentRateLimit_belowThreshold(true, true);
  }

  @Test
  public void checkFulfilmentRateLimit_aboveThreshold() throws Exception {
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
      rateLimiterClient.checkFulfilmentRateLimit(
          domain, product, caseType, "123.111.222.23", uprn, "0171 3434");
      fail();
    } catch (ResponseStatusException e) {
      assertEquals(failureException, e);
      assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
    }
  }

  @Test
  public void checkFulfilmentRateLimit_limiterOtherError() throws Exception {
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

    // Circuit breaker spots that this isn't a TOO_MANY_REQUESTS HttpStatus failure, so
    // we log an error and allow the limit check to pass. ie, no exception thrown
    rateLimiterClient.checkFulfilmentRateLimit(domain, product, caseType, null, uprn, null);
  }

  @Test
  public void checkFulfilmentRateLimit_corruptedLimiterJson() throws Exception {
    // This test simulates an internal error in which the call to the limiter has responded with a
    // 429 but the response JSon has somehow been corrupted
    String corruptedJson = "aoeu<.p#$%^EOUAEOU3245";
    ResponseStatusException failureException =
        new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, corruptedJson, null);
    Mockito.when(restClient.postResource(eq("/json"), any(), eq(RateLimitResponse.class), eq("")))
        .thenThrow(failureException);

    // Although the rest client call fails the circuit breaker allows the limit check to pass. ie,
    // no exception thrown
    rateLimiterClient.checkFulfilmentRateLimit(domain, product, caseType, null, uprn, null);
  }

  @Test
  public void checkFulfilmentRateLimit_nullProduct() {
    CTPException exception =
        assertThrows(
            CTPException.class,
            () -> {
              rateLimiterClient.checkFulfilmentRateLimit(
                  domain, null, caseType, null, uprn, "0171 3434");
            });
    assertTrue(exception.getMessage(), exception.getMessage().contains("cannot be null"));
  }

  @Test
  public void checkFulfilmentRateLimit_nullCaseType() {
    CTPException exception =
        assertThrows(
            CTPException.class,
            () -> {
              rateLimiterClient.checkFulfilmentRateLimit(
                  domain, product, null, null, uprn, "0171 3434");
            });
    assertTrue(exception.getMessage(), exception.getMessage().contains("cannot be null"));
  }

  @Test
  public void checkFulfilmentRateLimit_blankIpAddress() {
    CTPException exception =
        assertThrows(
            CTPException.class,
            () -> {
              rateLimiterClient.checkFulfilmentRateLimit(
                  domain, product, caseType, " ", uprn, "0171 3434");
            });
    assertTrue(exception.getMessage(), exception.getMessage().contains("cannot be blank"));
  }

  @Test
  public void checkFulfilmentRateLimit_nullUprn() {
    CTPException exception =
        assertThrows(
            CTPException.class,
            () -> {
              rateLimiterClient.checkFulfilmentRateLimit(
                  domain, product, caseType, null, null, "0171 3434");
            });
    assertTrue(exception.getMessage(), exception.getMessage().contains("cannot be null"));
  }

  @Test
  public void checkFulfilmentRateLimit_blankTelNo() {
    CTPException exception =
        assertThrows(
            CTPException.class,
            () -> {
              rateLimiterClient.checkFulfilmentRateLimit(domain, product, caseType, null, uprn, "");
            });
    assertTrue(exception.getMessage(), exception.getMessage().contains("cannot be blank"));
  }

  private void docheckFulfilmentRateLimit_belowThreshold(boolean useTelNo, boolean useIpAddress)
      throws CTPException {

    // Don't need to mock the call to restClient.postResource() as default is treated as being below
    // the limit

    String telNo = useTelNo ? "0123 3434333" : null;
    String ipAddress = useIpAddress ? "123.123.123.123" : null;
    rateLimiterClient.checkFulfilmentRateLimit(domain, product, caseType, ipAddress, uprn, telNo);

    // Grab the request sent to the limiter
    ArgumentCaptor<RateLimitRequest> limitRequestCaptor =
        ArgumentCaptor.forClass(RateLimitRequest.class);
    Mockito.verify(restClient).postResource(any(), limitRequestCaptor.capture(), any(), any());
    RateLimitRequest request = limitRequestCaptor.getValue();

    // Verify that the limit request contains the correct number of descriptors
    int expectedNumDescriptors = 2;
    expectedNumDescriptors += useTelNo ? 2 : 0;
    expectedNumDescriptors += useIpAddress ? 1 : 0;
    assertEquals(expectedNumDescriptors, request.getDescriptors().size());

    // Verify that the limit request is correct, for whatever combination of mandatory and
    // optional data we are currently testing
    int i = 0;
    verifyDescriptor(request, i++, product, caseType, "uprn", Long.toString(uprn.getValue()));
    if (useTelNo) {
      verifyDescriptor(request, i++, product, caseType, "telNo", telNo);
    }
    verifyDescriptor(request, i++, product, "uprn", Long.toString(uprn.getValue()));
    if (useTelNo) {
      verifyDescriptor(request, i++, product, "telNo", telNo);
    }
    if (useIpAddress) {
      verifyDescriptor(request, i++, product, "ipAddress", ipAddress);
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
    verifyEntry(descriptor, 0, "deliveryChannel", product.getDeliveryChannel().name());
    verifyEntry(descriptor, 1, "productGroup", product.getProductGroup().name());
    verifyEntry(descriptor, 2, "individual", Boolean.toString(product.getIndividual()));
    verifyEntry(descriptor, 3, "caseType", caseType.name());
    verifyEntry(descriptor, 4, finalKeyName, finalKeyValue);
  }

  private void verifyDescriptor(
      RateLimitRequest request,
      int index,
      Product product,
      String finalKeyName,
      String finalKeyValue) {
    LimitDescriptor descriptor = request.getDescriptors().get(index);
    assertEquals(2, descriptor.getEntries().size());
    verifyEntry(descriptor, 0, "deliveryChannel", product.getDeliveryChannel().name());
    verifyEntry(descriptor, 1, finalKeyName, finalKeyValue);
  }

  private void verifyEntry(
      LimitDescriptor descriptor, int index, String expectedKey, String expectedValue) {
    DescriptorEntry entry = descriptor.getEntries().get(index);
    assertEquals(expectedKey, entry.getKey());
    assertEquals(expectedValue, entry.getValue());
  }

  private void simulateCircuitBreaker() {
    doAnswer(
            new Answer<Object>() {
              @SuppressWarnings("unchecked")
              @Override
              public Object answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                Supplier<Object> runner = (Supplier<Object>) args[0];
                Function<Throwable, Object> fallback = (Function<Throwable, Object>) args[1];

                try {
                  // execute the circuitBreaker.run first argument (the Supplier for the code you
                  // want to run)
                  return runner.get();
                } catch (Throwable t) {
                  // execute the circuitBreaker.run second argument (the fallback Function)
                  fallback.apply(t);
                }
                return null;
              }
            })
        .when(circuitBreaker)
        .run(any(), any());
  }
}
