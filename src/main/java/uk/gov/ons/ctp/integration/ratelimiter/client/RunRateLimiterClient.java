package uk.gov.ons.ctp.integration.ratelimiter.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.HashMap;
import java.util.Map;
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
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitResponse;

public class RunRateLimiterClient {

  private RestClient restClient;
  private RateLimiterClient client;

  private ObjectMapper objectMapper;

  public static void main(String[] args)
      throws JsonMappingException, JsonProcessingException, CTPException {
    String limiterHost = "localhost";
    if (args.length >= 1) {
      limiterHost = args[0];
    }
    System.out.println("Running test against limiter at: " + limiterHost);
    System.out.println();

    new RunRateLimiterClient(limiterHost).runTest();

    System.out.println("\n** Test completed without error **");
  }

  public RunRateLimiterClient(String limiterHost) {
    this.objectMapper = new ObjectMapper();
    objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

    // Create RestClient to call limiter
    RestClientConfig restClientConfig = new RestClientConfig("http", limiterHost, "8181", "", "");
    Map<HttpStatus, HttpStatus> httpErrorMapping = new HashMap<>();
    httpErrorMapping.put(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS);
    this.restClient =
        new RestClient(restClientConfig, httpErrorMapping, HttpStatus.INTERNAL_SERVER_ERROR);

    this.client = new RateLimiterClient(this.restClient);
  }

  public void runTest() throws JsonProcessingException, CTPException {
    invokeLimitEndpoint("1) /limit?enabled=false", false);
    invokeJsonEndpoint("2) /json", HttpStatus.OK);

    invokeLimitEndpoint("3) /limit?enabled=true", true);
    invokeJsonEndpoint("4) /json", HttpStatus.TOO_MANY_REQUESTS);
  }

  private void invokeLimitEndpoint(String narrative, boolean tooManyRequests) {
    System.out.println(narrative);

    Map<String, String> headerParams = new HashMap<>();

    MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
    queryParams.add("enabled", Boolean.toString(tooManyRequests));

    String response =
        restClient.postResource("limit", null, String.class, headerParams, queryParams, "");
    System.out.println(response);
    System.out.println();
  }

  private void invokeJsonEndpoint(String narrative, HttpStatus expectedHttpStatus)
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
      // Get client to call /json endpoint
      RateLimitResponse response =
          client.checkRateLimit(
              RateLimiterClient.Domain.RHSvc,
              product,
              CaseType.HH,
              "1.23.34.45",
              new UniquePropertyReferenceNumber("24234234"),
              "0123 3434333");
      System.out.println("Response:");
      System.out.println(convertToJson(response));
      actualHttpStatus = HttpStatus.valueOf(Integer.parseInt(response.getOverallCode()));
    } catch (ResponseStatusException e) {
      actualHttpStatus = e.getStatus();
      System.out.println("InvokeJsonEndpoint: Caught exception: " + actualHttpStatus);
    }

    if (expectedHttpStatus != actualHttpStatus) {
      System.out.println(
          "FAILED: expected: " + expectedHttpStatus + " but actual: " + actualHttpStatus);
      System.exit(-1);
    }
    System.out.println();
  }

  private String convertToJson(Object o) throws JsonProcessingException {
    return objectMapper.writeValueAsString(o);
  }
}
