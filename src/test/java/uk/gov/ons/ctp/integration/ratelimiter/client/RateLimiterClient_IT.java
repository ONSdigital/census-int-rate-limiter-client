package uk.gov.ons.ctp.integration.ratelimiter.client;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.Before;
import org.junit.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.ons.ctp.common.domain.CaseType;
import uk.gov.ons.ctp.common.domain.UniquePropertyReferenceNumber;
import uk.gov.ons.ctp.common.error.CTPException;
import uk.gov.ons.ctp.common.rest.RestClient;
import uk.gov.ons.ctp.common.rest.RestClientConfig;
import uk.gov.ons.ctp.integration.common.product.model.Product;
import uk.gov.ons.ctp.integration.common.product.model.Product.DeliveryChannel;
import uk.gov.ons.ctp.integration.common.product.model.Product.ProductGroup;

/**
 * This is a program for manually testing the client against an real/fake limiter.
 *
 * <p>The hostname of the limiter should be set in the LIMITER_HOST environment variable. If this
 * environment variable is not set then the program doesn't do anything (and will therefore 'pass'
 * the test). For running on a development machine set this to 'localhost'.
 */
public class RateLimiterClient_IT {
  private static final String PASSWORD = "password";
  private String limiterHost;

  private RestClient restClient;
  private RateLimiterClient client;
  private CircuitBreaker circuitBreaker;

  private ObjectMapper objectMapper;

  @Before
  public void setup() throws CTPException {

    limiterHost = System.getenv("LIMITER_HOST");

    if (limiterHost == null) {
      System.out.println();
      System.out.println(
          "**********************************************************************************");
      System.out.println(
          "*** NOT running test. No limiter host set in environment variable LIMITER_HOST ***");
      System.out.println(
          "**********************************************************************************");
      System.out.println();
    } else {
      System.out.println("Running test against limiter at: " + limiterHost);
      System.out.println();

      this.objectMapper = new ObjectMapper();
      objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

      // Create RestClient to call limiter
      int connectionManagerDefaultMaxPerRoute = 20;
      int connectionManagerMaxTotal = 50;

      int connectTimeoutMillis = 0;
      int connectionRequestTimeoutMillis = 0;
      int socketTimeoutMillis = 0;

      RestClientConfig restClientConfig =
          new RestClientConfig(
              "http",
              limiterHost,
              "8181",
              "",
              "",
              connectionManagerDefaultMaxPerRoute,
              connectionManagerMaxTotal,
              connectTimeoutMillis,
              connectionRequestTimeoutMillis,
              socketTimeoutMillis);
      Map<HttpStatus, HttpStatus> httpErrorMapping = new HashMap<>();
      httpErrorMapping.put(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS);
      this.restClient =
          new RestClient(restClientConfig, httpErrorMapping, HttpStatus.INTERNAL_SERVER_ERROR);

      // Create minimalist circuit breaker implementation
      this.circuitBreaker =
          new CircuitBreaker() {
            public <T> T run(Supplier<T> toRun, Function<Throwable, T> fallback) {
              try {
                // execute the code that the circuitBreaker is protecting
                return toRun.get();
              } catch (Throwable t) {
                // execute the fallback Function)
                fallback.apply(t);
                return null;
              }
            }
          };

      this.client = new RateLimiterClient(this.restClient, circuitBreaker, PASSWORD);
    }
  }

  @Test
  public void runFulfilmentLimitTest() throws JsonProcessingException, CTPException {
    if (limiterHost == null) {
      return;
    }

    invokeLimitEnabledEndpoint("1) /limit?enabled=false", false);
    invokeFulfilmentRateLimitCheck("2) no telNo or IP /json", false, false, HttpStatus.OK);
    invokeFulfilmentRateLimitCheck("3) no telNo but with IP /json", false, true, HttpStatus.OK);
    invokeFulfilmentRateLimitCheck("4) telNo but no IP /json", true, false, HttpStatus.OK);
    invokeFulfilmentRateLimitCheck("5) telNo and IP /json", true, true, HttpStatus.OK);

    invokeLimitEnabledEndpoint("6) /limit?enabled=true", true);
    invokeFulfilmentRateLimitCheck("7) /json", false, false, HttpStatus.TOO_MANY_REQUESTS);

    System.out.println("\n** Test completed without error **");
  }

  @Test
  public void runWebformLimitTest() throws JsonProcessingException, CTPException {
    if (limiterHost == null) {
      return;
    }

    invokeLimitEnabledEndpoint("1) /limit?enabled=false", false);
    invokeWebformRateLimitCheck("2) Webform check below limit", HttpStatus.OK);

    invokeLimitEnabledEndpoint("3) /limit?enabled=true", true);
    invokeWebformRateLimitCheck("4) Webform check above limit", HttpStatus.TOO_MANY_REQUESTS);

    System.out.println("\n** Test completed without error **");
  }

  private void invokeLimitEnabledEndpoint(String narrative, boolean tooManyRequests) {
    System.out.println(narrative);

    Map<String, String> headerParams = new HashMap<>();

    MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
    queryParams.add("enabled", Boolean.toString(tooManyRequests));

    String response =
        restClient.postResource("limit", null, String.class, headerParams, queryParams, "");
    System.out.println(response);
    System.out.println();
  }

  private void invokeFulfilmentRateLimitCheck(
      String narrative, boolean useTelNo, boolean useIP, HttpStatus expectedHttpStatus)
      throws JsonProcessingException, CTPException {
    System.out.println(narrative);
    System.out.println("Expecting: " + expectedHttpStatus.name());

    Product product =
        new Product(
            "F1",
            ProductGroup.QUESTIONNAIRE,
            "Big print",
            null,
            true,
            null,
            DeliveryChannel.SMS,
            null,
            null,
            null);

    HttpStatus actualHttpStatus;
    try {
      String ipAddress = useIP ? "1.23.34.45" : null;
      String telNo = useTelNo ? "0123 3434333" : null;

      // Get client to call /json endpoint
      client.checkFulfilmentRateLimit(
          RateLimiterClient.Domain.RH,
          product,
          CaseType.HH,
          ipAddress,
          new UniquePropertyReferenceNumber("24234234"),
          telNo);
      actualHttpStatus = HttpStatus.OK;

    } catch (ResponseStatusException e) {
      actualHttpStatus = e.getStatus();
      System.out.println("invokeFulfilmentRateLimitCheck: Caught exception: " + actualHttpStatus);
      System.out.println("Response:");
      System.out.println(e.getReason());
    }

    assertEquals(expectedHttpStatus, actualHttpStatus);
    System.out.println();
  }

  private void invokeWebformRateLimitCheck(String narrative, HttpStatus expectedHttpStatus)
      throws JsonProcessingException, CTPException {
    System.out.println(narrative);
    System.out.println("Expecting: " + expectedHttpStatus.name());

    HttpStatus actualHttpStatus;
    try {
      // Get client to call /json endpoint
      client.checkWebformRateLimit(RateLimiterClient.Domain.RH, "100.233.73.101");
      actualHttpStatus = HttpStatus.OK;

    } catch (ResponseStatusException e) {
      actualHttpStatus = e.getStatus();
      System.out.println("invokeWebformRateLimitCheck: Caught exception: " + actualHttpStatus);
      System.out.println(e.getReason());
    }

    assertEquals(expectedHttpStatus, actualHttpStatus);
    System.out.println();
  }
}
