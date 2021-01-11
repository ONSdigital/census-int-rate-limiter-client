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
import uk.gov.ons.ctp.common.FixtureHelper;
import uk.gov.ons.ctp.common.domain.CaseType;
import uk.gov.ons.ctp.common.domain.UniquePropertyReferenceNumber;
import uk.gov.ons.ctp.common.error.CTPException;
import uk.gov.ons.ctp.integration.common.product.model.Product;
import uk.gov.ons.ctp.integration.common.product.model.Product.DeliveryChannel;
import uk.gov.ons.ctp.integration.common.product.model.Product.ProductGroup;
import uk.gov.ons.ctp.integration.ratelimiter.model.LimitDescriptor;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitRequest;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitResponse;

/** This class contains unit tests for limit testing fulfilment requests. */
@RunWith(MockitoJUnitRunner.class)
public class RateLimiterClientFulfilmentTest extends RateLimiterClientTestBase {

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

  private UniquePropertyReferenceNumber uprn = new UniquePropertyReferenceNumber("24234234");
  private CaseType caseType = CaseType.HH;

  @Test(expected = IllegalArgumentException.class)
  public void shouldRejectNullEncryptionPassword() {
    new RateLimiterClient(restClient, circuitBreaker, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldRejectBlankEncryptionPassword() {
    new RateLimiterClient(restClient, circuitBreaker, "");
  }

  @Test
  public void checkFulfilmentRateLimit_nullDomain() {
    CTPException exception =
        assertThrows(
            CTPException.class,
            () ->
                rateLimiterClient.checkFulfilmentRateLimit(
                    null, product, caseType, AN_IPv4_ADDRESS, uprn, "0171 3434"));
    assertTrue(exception.getMessage(), exception.getMessage().contains("'domain' cannot be null"));
    verifyEnvoyLimiterNotCalled();
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
  public void shouldAcceptValidIpv4ClientIpAndMakeUseOfIt() throws Exception {
    doCheckIpAddressUsed(AN_IPv4_ADDRESS, true);
  }

  @Test
  public void shouldQuietlyAcceptNullClientIpButNotUseIt() throws Exception {
    doCheckIpAddressUsed(null, false);
  }

  @Test
  public void shouldQuietlyAcceptEmptyClientIpButNotUseIt() throws Exception {
    doCheckIpAddressUsed("", false);
  }

  @Test
  public void shouldQuietlyAcceptBadlyFormattedClientIpButNotUseIt() throws Exception {
    doCheckIpAddressUsed("badlyformattedIpAddress", false);
  }

  @Test
  public void shouldQuietlyAcceptIpV6ClientIpButNotUseIt() throws Exception {
    doCheckIpAddressUsed("2001:DB8::21f:5bff:febf:ce22:8a2e", false);
  }

  @Test
  public void checkFulfilmentRateLimit_aboveThreshold() throws Exception {
    // Limit request is going to fail with exception. This needs to contain a string with the
    // limiters
    // too-many-requests response
    RateLimitResponse resp = FixtureHelper.loadClassFixtures(RateLimitResponse[].class).get(0);
    ResponseStatusException failureException = overTheLimitException(resp);
    mockRateLimitException(failureException);

    // Confirm that limiter request fails with a 429 exception
    try {
      rateLimiterClient.checkFulfilmentRateLimit(
          domain, product, caseType, AN_IPv4_ADDRESS, uprn, "0171 3434");
      fail();
    } catch (ResponseStatusException e) {
      assertEquals(failureException, e);
      assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
    }
    verifiedRequestSentToLimiter();
  }

  @Test
  public void checkFulfilmentRateLimit_limiterOtherError() throws Exception {
    // Limit request is going to fail with exception that simulates an unexpected error from the
    // limiter. ie, http
    // response status is neither an expected 200 or 429
    mockRateLimitException(badRequestException());

    // Circuit breaker spots that this isn't a TOO_MANY_REQUESTS HttpStatus failure, so
    // we log an error and allow the limit check to pass. ie, no exception thrown
    rateLimiterClient.checkFulfilmentRateLimit(domain, product, caseType, null, uprn, null);
    verifiedRequestSentToLimiter();
  }

  @Test
  public void checkFulfilmentRateLimit_corruptedLimiterJson() throws Exception {
    // This test simulates an internal error in which the call to the limiter has responded with a
    // 429 but the response JSon has somehow been corrupted
    mockRateLimitException(corruptedJsonException());

    // Although the rest client call fails the circuit breaker allows the limit check to pass. ie,
    // no exception thrown
    rateLimiterClient.checkFulfilmentRateLimit(domain, product, caseType, null, uprn, null);
    verifiedRequestSentToLimiter();
  }

  @Test
  public void checkFulfilmentRateLimit_worksWithCircuitBreakerOpen() throws Exception {
    // Simulate circuit breaker not calling rest client
    mockRateLimitException(circuitBreakerOpenException);

    // Limit check works without an exception
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
    verifyEnvoyLimiterNotCalled();
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
    verifyEnvoyLimiterNotCalled();
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
    verifyEnvoyLimiterNotCalled();
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
    String ipAddress = useIpAddress ? AN_IPv4_ADDRESS : null;
    rateLimiterClient.checkFulfilmentRateLimit(domain, product, caseType, ipAddress, uprn, telNo);

    // Grab the request sent to the limiter
    RateLimitRequest request = verifiedRequestSentToLimiter();

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

  private void doCheckIpAddressUsed(String ipAddress, boolean expectIpUsed) throws Exception {
    rateLimiterClient.checkFulfilmentRateLimit(
        domain, product, caseType, ipAddress, uprn, "0123 3434333");

    RateLimitRequest request = verifiedRequestSentToLimiter();

    int expectedNumDescriptors = 4;

    if (expectIpUsed) {
      verifyDescriptor(request, expectedNumDescriptors++, product, "ipAddress", ipAddress);
    }
    assertEquals(expectedNumDescriptors, request.getDescriptors().size());
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
}
