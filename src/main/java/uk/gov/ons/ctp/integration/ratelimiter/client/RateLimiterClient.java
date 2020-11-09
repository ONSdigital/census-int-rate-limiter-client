package uk.gov.ons.ctp.integration.ratelimiter.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.godaddy.logging.Logger;
import com.godaddy.logging.LoggerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.ons.ctp.common.domain.CaseType;
import uk.gov.ons.ctp.common.domain.UniquePropertyReferenceNumber;
import uk.gov.ons.ctp.common.error.CTPException;
import uk.gov.ons.ctp.common.error.CTPException.Fault;
import uk.gov.ons.ctp.common.rest.RestClient;
import uk.gov.ons.ctp.integration.common.product.model.Product;
import uk.gov.ons.ctp.integration.ratelimiter.model.DescriptorEntry;
import uk.gov.ons.ctp.integration.ratelimiter.model.LimitDescriptor;
import uk.gov.ons.ctp.integration.ratelimiter.model.LimitStatus;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitRequest;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitResponse;

public class RateLimiterClient {
  private static final Logger log = LoggerFactory.getLogger(RateLimiterClient.class);

  // Enum with domains known to limiter
  public enum Domain {
    RH("respondenthome");

    private String domainName;

    private Domain(String domainName) {
      this.domainName = domainName;
    }
  }

  // Names of descriptor entries for limiter request
  private static final String DESC_PRODUCT_GROUP = "productGroup";
  private static final String DESC_INDIVIDUAL = "individual";
  private static final String DESC_DELIVERY_CHANNEL = "deliveryChannel";
  private static final String DESC_CASE_TYPE = "caseType";
  private static final String DESC_IP_ADDRESS = "ipAddress";
  private static final String DESC_UPRN = "uprn";
  private static final String DESC_TEL_NO = "telNo";

  private static String[] DESCRIPTORS_WITH_UPRN = {
    DESC_DELIVERY_CHANNEL, DESC_PRODUCT_GROUP, DESC_INDIVIDUAL, DESC_CASE_TYPE, DESC_UPRN
  };
  private static String[] DESCRIPTORS_WITH_TEL_NO = {
    DESC_DELIVERY_CHANNEL, DESC_PRODUCT_GROUP, DESC_INDIVIDUAL, DESC_CASE_TYPE, DESC_TEL_NO
  };
  private static String[] DELIVERYCHANNEL_WITH_ONLY_UPRN = {DESC_DELIVERY_CHANNEL, DESC_UPRN};
  private static String[] DELIVERYCHANNEL_WITH_ONLY_TEL_NO = {DESC_DELIVERY_CHANNEL, DESC_TEL_NO};
  private static String[] DELIVERYCHANNEL_WITH_ONLY_IP_ADDRESS = {
    DESC_DELIVERY_CHANNEL, DESC_IP_ADDRESS
  };

  private static final String RATE_LIMITER_QUERY_PATH = "/json";

  private RestClient rateLimiterClient;
  private ObjectMapper objectMapper = new ObjectMapper();

  public RateLimiterClient(RestClient rateLimiterClient) {
    super();
    this.rateLimiterClient = rateLimiterClient;

    this.objectMapper = new ObjectMapper();
    this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
  }

  /**
   * Send limit request to the limiter.
   *
   * <p>If no limit has been breached then this method returns. If there is an internal error or if
   * a limit is breached then an exception is thrown.
   *
   * <p>All arguments must be non null and not empty, with the exception of the phone number which
   * can be null if not known.
   *
   * @param domain is the domain to query against. This value is mandatory.
   * @param product is the product used by the caller. This value is mandatory.
   * @param caseType is the case type for the current request. This value is mandatory.
   * @param ipAddress is the end users ip address. This value can be null, but if supplied then it
   *     cannot be an empty string.
   * @param uprn is the uprn to limit requests against. This value is mandatory.
   * @param telNo is the end users telephone number. This value can be null, but if supplied then it
   *     cannot be an empty string.
   * @return The response from the rate limiter.
   * @throws CTPException if there is a processing error or if an invalid argument is supplied.
   * @throws ResponseStatusException if the request to the limiter didn't return a 200. If a limit
   *     has been breached then the exception status will be HttpStatus.TOO_MANY_REQUESTS and the
   *     exception's reason field will contain the limiters json response.
   */
  public RateLimitResponse checkRateLimit(
      Domain domain,
      Product product,
      CaseType caseType,
      String ipAddress,
      UniquePropertyReferenceNumber uprn,
      String telNo)
      throws CTPException, ResponseStatusException {

    // Fail if caller doesn't meet interface requirements
    verifyArgumentSupplied("domain", domain);
    verifyArgumentSupplied("product", product);
    verifyArgumentSupplied("caseType", caseType);
    verifyArgumentNotEmpty("ipAddress", ipAddress);
    verifyArgumentSupplied("uprn", uprn);
    verifyArgumentNotEmpty("telNo", telNo);

    log.with("domain", domain.domainName)
        .with("productGroup", product.getProductGroup().name())
        .with("individual", product.getIndividual().toString())
        .with("deliveryChannel", product.getDeliveryChannel().name())
        .with("caseType", caseType.name())
        .with("ipAddress", ipAddress)
        .with("uprn", uprn.getValue())
        .with("telNo", redactTelephoneNumber(telNo))
        .info("Going to call Rate Limiter Service");

    // Make it easy to access limiter parameters by adding to a hashmap
    HashMap<String, String> params = new HashMap<String, String>();
    params.put(DESC_PRODUCT_GROUP, product.getProductGroup().name());
    params.put(DESC_INDIVIDUAL, product.getIndividual().toString());
    params.put(DESC_DELIVERY_CHANNEL, product.getDeliveryChannel().name());
    params.put(DESC_CASE_TYPE, caseType.name());
    params.put(DESC_IP_ADDRESS, ipAddress);
    params.put(DESC_UPRN, Long.toString(uprn.getValue()));
    params.put(DESC_TEL_NO, telNo);

    // Create request
    RateLimitRequest request = createRateLimitRequest(domain, params);
    log.with(request).debug("RateLimiterRequest");

    // Send request to limiter, with detailed logging if we breached a limit
    RateLimitResponse response;
    try {
      response =
          rateLimiterClient.postResource(
              RATE_LIMITER_QUERY_PATH, request, RateLimitResponse.class, "");

    } catch (ResponseStatusException limiterException) {
      HttpStatus httpStatus = limiterException.getStatus();
      if (httpStatus == HttpStatus.TOO_MANY_REQUESTS) {
        // Explain which LimitDescriptor(s) triggered the limit breach
        StringBuilder failureDescription = new StringBuilder("Rate limit(s) breached:");
        String responseJson = limiterException.getReason();
        log.with("responseJson", responseJson).debug("Limiter response");
        response = convertJsonToObject(responseJson);
        for (int i = 0; i < response.getStatuses().size(); i++) {
          LimitStatus breachedLimit = response.getStatuses().get(i);
          if (breachedLimit.getCode().equals(LimitStatus.CODE_LIMIT_BREACHED)) {
            failureDescription.append(" ");
            failureDescription.append(describeLimitBreach(request, i));
          }
        }
        log.info(failureDescription.toString());
      } else {
        // Something unexpected went wrong
        log.warn("Limiter request failed");
        throw new CTPException(
            Fault.SYSTEM_ERROR,
            limiterException,
            "POST request to limiter failed with http status: "
                + httpStatus.value()
                + "("
                + httpStatus.name()
                + ")");
      }
      throw limiterException;
    }

    return response;
  }

  // Throws CTPException is the argument is null
  private void verifyArgumentSupplied(String argName, Object argValue) throws CTPException {
    if (argValue == null) {
      throw new CTPException(Fault.SYSTEM_ERROR, "Argument '" + argName + "' cannot be null");
    }
  }

  // Throws CTPException if an argument is supplied but it is blank
  private void verifyArgumentNotEmpty(String argName, String argValue) throws CTPException {
    if (argValue != null && argValue.isBlank()) {
      throw new CTPException(
          Fault.SYSTEM_ERROR, "Argument '" + argName + "' cannot be blank (" + argValue + ")");
    }
  }

  // This is a key method that bunches together the various arguments that the limiter will be using
  // to decide if the request has breached any limits.
  private RateLimitRequest createRateLimitRequest(
      Domain domain, HashMap<String, String> descriptorData) {

    boolean haveTelNo = descriptorData.get(DESC_TEL_NO) != null;
    boolean haveIpAddress = descriptorData.get(DESC_IP_ADDRESS) != null;

    List<LimitDescriptor> descriptors = new ArrayList<>();
    descriptors.add(createLimitDescriptor(DESCRIPTORS_WITH_UPRN, descriptorData));
    if (haveTelNo) {
      descriptors.add(createLimitDescriptor(DESCRIPTORS_WITH_TEL_NO, descriptorData));
    }
    descriptors.add(createLimitDescriptor(DELIVERYCHANNEL_WITH_ONLY_UPRN, descriptorData));
    if (haveTelNo) {
      descriptors.add(createLimitDescriptor(DELIVERYCHANNEL_WITH_ONLY_TEL_NO, descriptorData));
    }
    if (haveIpAddress) {
      descriptors.add(createLimitDescriptor(DELIVERYCHANNEL_WITH_ONLY_IP_ADDRESS, descriptorData));
    }

    RateLimitRequest request =
        RateLimitRequest.builder().domain(domain.domainName).descriptors(descriptors).build();
    return request;
  }

  private LimitDescriptor createLimitDescriptor(
      String[] descriptorList, HashMap<String, String> descriptorData) {

    List<DescriptorEntry> entries = new ArrayList<>();
    for (String descriptorName : descriptorList) {
      String descriptorValue = descriptorData.get(descriptorName);
      entries.add(new DescriptorEntry(descriptorName, descriptorValue));
    }

    LimitDescriptor limitDescriptor = new LimitDescriptor();
    limitDescriptor.setEntries(entries);

    return limitDescriptor;
  }

  // Build a string to summarise the data which triggered a limit breach
  private String describeLimitBreach(RateLimitRequest request, int i) {
    int failureNumber = i + 1;
    StringBuilder desc = new StringBuilder("(" + failureNumber + ") ");

    LimitDescriptor failingDescriptor = request.getDescriptors().get(i);

    boolean needComma = false;
    for (DescriptorEntry descriptorEntry : failingDescriptor.getEntries()) {
      if (needComma) {
        desc.append(", ");
      } else {
        needComma = true;
      }

      String descriptorKey = descriptorEntry.getKey();
      String descriptorValue = descriptorEntry.getValue();
      if (descriptorKey.equals(DESC_TEL_NO)) {
        descriptorValue = redactTelephoneNumber(descriptorValue);
      }

      desc.append(descriptorKey + "=" + descriptorValue);
    }

    return desc.toString();
  }

  private RateLimitResponse convertJsonToObject(String responseJson) throws CTPException {
    RateLimitResponse response;

    try {
      response = objectMapper.readValue(responseJson, RateLimitResponse.class);
    } catch (JsonProcessingException jsonException) {
      log.with("jsonResponse", responseJson)
          .warn("Failed to parse rate limiter exception response");
      throw new CTPException(
          Fault.SYSTEM_ERROR, jsonException, "Failed to parse rate limiter exception response");
    }

    return response;
  }

  private String redactTelephoneNumber(String telNo) {
    if (telNo == null) {
      return "null";
    }

    StringBuilder redactedTelephoneNumber = new StringBuilder();

    redactedTelephoneNumber.append("xxxx");
    redactedTelephoneNumber.append(telNo.substring(telNo.length() - 2));

    return redactedTelephoneNumber.toString();
  }
}
