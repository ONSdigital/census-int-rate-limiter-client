package uk.gov.ons.ctp.integration.ratelimiterclient;

import static org.junit.Assert.assertEquals;
import java.util.HashMap;
import java.util.Map;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import uk.gov.ons.ctp.common.rest.RestClient;
import uk.gov.ons.ctp.common.rest.RestClientConfig;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitRequest;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitResponse;

public class RateLimiterClientTest {
  private ObjectMapper objectMapper;
  
  private RestClient restClient;
  
  
  public static void main(String[] args) throws JsonMappingException, JsonProcessingException {

    RateLimiterClientTest rateLimiterClientTest = new RateLimiterClientTest();

    rateLimiterClientTest.setLimit(false);
    rateLimiterClientTest.callJsonEndpoint("200");
    
    rateLimiterClientTest.setLimit(true);
    rateLimiterClientTest.callJsonEndpoint("200");
  }
  
  private void setLimit(boolean tooManyRequests) {
    Map<String, String> headerParams = new HashMap<>();
    
    MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
    queryParams.add("enabled", Boolean.toString(tooManyRequests));
    
    String response = restClient.postResource("limit", null, String.class, headerParams, queryParams, "");
    System.out.println(response);
    
  }

  public RateLimiterClientTest() {
    this.objectMapper = new ObjectMapper();
    objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    
    RestClientConfig restClientConfig = new RestClientConfig("http", "localhost", "8181", "", "");
    this.restClient = new RestClient(restClientConfig);
  }
  
  private void callJsonEndpoint(String expectedHttpStatus) throws JsonProcessingException {
    
    RateLimitRequest req = createRateLimitRequest();
    
    RateLimitResponse response = restClient.postResource("json", req, RateLimitResponse.class, "");
    System.out.println(convertToJson(response));
    
    String actualHttpStatus = response.getOverallCode();
    assertEquals(expectedHttpStatus, actualHttpStatus);
  }
  
  private RateLimitRequest createRateLimitRequest() throws JsonMappingException, JsonProcessingException {
    String json = "{\n" + 
        "  \"domain\": \"respondenthome\",\n" + 
        "  \"descriptors\": [\n" + 
        "    {\n" + 
        "      \"entries\": [\n" + 
        "        {\"key\": \"productGroup\", \"value\":  \"UAC\"},\n" + 
        "        {\"key\": \"individual\", \"value\":  \"false\"},\n" + 
        "        {\"key\": \"deliveryChannel\", \"value\":  \"SMS\"},\n" + 
        "        {\"key\": \"caseType\", \"value\":  \"HH\"},\n" + 
        "        {\"key\": \"uprn\", \"value\":  \"987\"}\n" + 
        "      ]\n" + 
        "    },\n" + 
        "    {\n" + 
        "      \"entries\": [\n" + 
        "        {\"key\": \"productGroup\", \"value\":  \"UAC\"},\n" + 
        "        {\"key\": \"individual\", \"value\":  \"false\"},\n" + 
        "        {\"key\": \"deliveryChannel\", \"value\":  \"SMS\"},\n" + 
        "        {\"key\": \"caseType\", \"value\":  \"HH\"},\n" + 
        "        {\"key\": \"telNo\", \"value\":  \"07968583119\"}\n" + 
        "      ]\n" + 
        "    },\n" + 
        "    {\n" + 
        "      \"entries\": [\n" + 
        "        {\"key\": \"productGroup\", \"value\":  \"UAC\"},\n" + 
        "        {\"key\": \"individual\", \"value\":  \"false\"},\n" + 
        "        {\"key\": \"deliveryChannel\", \"value\":  \"SMS\"},\n" + 
        "        {\"key\": \"caseType\", \"value\":  \"HH\"},\n" + 
        "        {\"key\": \"ipAddress\", \"value\":  \"123.123.123.123\"}\n" + 
        "      ]\n" + 
        "    }\n" + 
        "  ]\n" + 
        "}";
    
    RateLimitRequest request = objectMapper.readValue(json, RateLimitRequest.class);
    
    return request;
  }
  
  private String convertToJson(Object o) throws JsonProcessingException {
    return objectMapper.writeValueAsString(o);
  }
}
