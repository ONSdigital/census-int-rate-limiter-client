package uk.gov.ons.ctp.integration.ratelimiter.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.godaddy.logging.Logger;
import com.godaddy.logging.LoggerFactory;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
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
import uk.gov.ons.ctp.integration.ratelimiter.util.Encryptor;

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

  // Names of descriptor entries for limiter requests
  private static final String DESC_PRODUCT_GROUP = "productGroup";
  private static final String DESC_INDIVIDUAL = "individual";
  private static final String DESC_DELIVERY_CHANNEL = "deliveryChannel";
  private static final String DESC_CASE_TYPE = "caseType";
  private static final String DESC_IP_ADDRESS = "ipAddress";
  private static final String DESC_UPRN = "uprn";
  private static final String DESC_TEL_NO = "telNo";
  private static final String DESC_REQUEST = "request";
  private static final String DESC_MODULO = "modulo";

  // Lists of descriptors to be sent to the limiter. Fulfilment requests only.
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

  private static String[] DESCRIPTORS_WEBFORM = {DESC_REQUEST, DESC_IP_ADDRESS};
  private static String[] DESCRIPTORS_EQ_LAUNCH = {DESC_REQUEST, DESC_MODULO};

  private static final String RATE_LIMITER_QUERY_PATH = "/json";

  private RestClient envoyLimiterRestClient;
  private CircuitBreaker circuitBreaker;
  private String encryptionPassword;
  private ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Constructor.
   *
   * @param envoyLimiterRestClient rest client
   * @param circuitBreaker circuit breaker
   * @param encryptionPassword encryption password (for encrypting the logging of telephone number.
   *     This cannot be null or empty.
   */
  public RateLimiterClient(
      RestClient envoyLimiterRestClient, CircuitBreaker circuitBreaker, String encryptionPassword) {
    super();
    this.envoyLimiterRestClient = envoyLimiterRestClient;
    this.circuitBreaker = circuitBreaker;
    this.encryptionPassword = encryptionPassword;

    if (StringUtils.isBlank(encryptionPassword)) {
      throw new IllegalArgumentException("Encryption password must be configured");
    }

    this.objectMapper = new ObjectMapper();
    this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
  }

  /**
   * Send fulfilment limit request to the limiter.
   *
   * <p>If no limit has been breached then this method returns. If there is a validation error or if
   * a limit is breached then an exception is thrown.
   *
   * <p>All arguments must be non null and not empty, with the exception of the phone number which
   * can be null if not known, and the ipAddress which can be null.
   *
   * @param domain is the domain to query against. This value is mandatory.
   * @param product is the product used by the caller. This value is mandatory.
   * @param caseType is the case type for the current request. This value is mandatory.
   * @param ipAddress is the end users IP address. If this is not a valid IPv4 address we do not add
   *     it to the rate limit descriptors.
   * @param uprn is the uprn to limit requests against. This value is mandatory.
   * @param telNo is the end users telephone number. This value can be null, but if supplied then it
   *     cannot be an empty string.
   * @throws CTPException if an invalid argument is supplied.
   * @throws ResponseStatusException if the request limit has been breached. In this case the
   *     exception status will be HttpStatus.TOO_MANY_REQUESTS and the exception's reason field will
   *     contain the limiters json response.
   */
  public void checkFulfilmentRateLimit(
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
    verifyArgumentSupplied("uprn", uprn);
    verifyArgumentNotEmpty("telNo", telNo);

    if (!isValidIpAddress(ipAddress)) {
      ipAddress = null;
    }

    log.with("domain", domain.domainName)
        .with("productGroup", product.getProductGroup().name())
        .with("individual", product.getIndividual().toString())
        .with("deliveryChannel", product.getDeliveryChannel().name())
        .with("caseType", caseType.name())
        .with("ipAddress", ipAddress)
        .with("uprn", uprn.getValue())
        .with("encrypted-telNo", encrypt(telNo))
        .info("Fulfilment rate limit. Going to call Rate Limiter Service");

    // Make it easy to access limiter parameters by adding to a hashmap
    Map<String, String> params = new HashMap<String, String>();
    params.put(DESC_PRODUCT_GROUP, product.getProductGroup().name());
    params.put(DESC_INDIVIDUAL, product.getIndividual().toString());
    params.put(DESC_DELIVERY_CHANNEL, product.getDeliveryChannel().name());
    params.put(DESC_CASE_TYPE, caseType.name());
    params.put(DESC_IP_ADDRESS, ipAddress);
    params.put(DESC_UPRN, Long.toString(uprn.getValue()));
    params.put(DESC_TEL_NO, telNo);

    // Create request
    RateLimitRequest request = createRateLimitRequestForFulfilment(domain, params);
    log.with(request).debug("RateLimiterRequest for fulfilment");

    // Send request to limiter
    invokeRateLimiter("fulfilments", request);
  }

  /**
   * Send webform limit request to the limiter.
   *
   * <p>If no limit has been breached then this method returns. If there is a validation error or if
   * a limit is breached then an exception is thrown.
   *
   * @param domain is the domain to query against. This value is mandatory.
   * @param ipAddress is the end users IP address. If this is not a valid IPv4 address we skip the
   *     check.
   * @throws CTPException if there is an invalid argument is supplied.
   * @throws ResponseStatusException if the request limit has been breached. In this case the
   *     exception status will be HttpStatus.TOO_MANY_REQUESTS and the exception's reason field will
   *     contain the limiters json response.
   */
  public void checkWebformRateLimit(Domain domain, String ipAddress)
      throws CTPException, ResponseStatusException {

    // Fail if caller doesn't meet interface requirements
    verifyArgumentSupplied("domain", domain);

    if (!isValidIpAddress(ipAddress)) {
      log.with("ipAddress", ipAddress)
          .info("Webform rate limit not checked due to invalid IP address");
      return;
    }

    log.with("ipAddress", ipAddress).info("Check webform rate limit");

    // Make it easy to access limiter parameters by adding to a hashmap
    Map<String, String> params = new HashMap<String, String>();
    params.put(DESC_REQUEST, "WEBFORM");
    params.put(DESC_IP_ADDRESS, ipAddress);

    // Create request
    RateLimitRequest request =
        createRateLimitRequestWithAllDescriptors(domain, params, DESCRIPTORS_WEBFORM);
    log.with(request).debug("RateLimiterRequest for Webform");

    // Send request to limiter
    invokeRateLimiter("webform", request);
  }

  /**
   * Send EQ Launch rate limit request to the limiter.
   *
   * <p>If no limit has been breached then this method returns. If there is a validation error or if
   * a limit is breached then an exception is thrown.
   *
   * @param domain is the domain to query against. This value is mandatory.
   * @param ipAddress is the end users IP address. If this is not a valid IPv4 address we skip the
   *     check.
   * @param loadSheddingModulus an integer for modulus calculations against the last octet of the IP
   *     address. This cannot be zero.
   * @throws CTPException if there is an invalid argument is supplied.
   * @throws ResponseStatusException if the request limit has been breached. In this case the
   *     exception status will be HttpStatus.TOO_MANY_REQUESTS and the exception's reason field will
   *     contain the limiters json response.
   */
  public void checkEqLaunchLimit(Domain domain, String ipAddress, int loadSheddingModulus)
      throws CTPException, ResponseStatusException {
    verifyArgumentSupplied("domain", domain);
    verifyLoadSheddingModulus(loadSheddingModulus);

    if (!isValidIpAddress(ipAddress)) {
      log.with("ipAddress", ipAddress)
          .info("EQ Launch rate limit not checked due to invalid IP address");
      return;
    }
    log.with("ipAddress", ipAddress)
        .with("loadSheddingModulus", loadSheddingModulus)
        .info("Check EQ Launch limit");

    Integer modulo = lastOctet(ipAddress) % loadSheddingModulus;

    var params = new HashMap<String, String>();
    params.put(DESC_REQUEST, "EQLAUNCH");
    params.put(DESC_MODULO, modulo.toString());

    RateLimitRequest request =
        createRateLimitRequestWithAllDescriptors(domain, params, DESCRIPTORS_EQ_LAUNCH);
    log.with(request).debug("RateLimiterRequest for EQ Launch");

    invokeRateLimiter("EQ Launch", request);
  }

  private Integer lastOctet(String ipAddress) {
    return Integer.valueOf(ipAddress.substring(ipAddress.lastIndexOf('.') + 1));
  }

  private boolean isValidIpAddress(String ipAddress) {
    boolean valid = true;
    if (StringUtils.isBlank(ipAddress)) {
      log.with("ipAddress", ipAddress)
          .warn("Cannot accept blank IP address. This will not be used for rate limit check");
      valid = false;
    }
    if (!InetAddressValidator.getInstance().isValidInet4Address(ipAddress)) {
      log.with("ipAddress", ipAddress)
          .warn("IP address is not valid IPv4 format. This will not be used for rate limit check");
      valid = false;
    }
    return valid;
  }

  private void verifyLoadSheddingModulus(int loadSheddingModulus) throws CTPException {
    if (loadSheddingModulus == 0) {
      throw new CTPException(Fault.SYSTEM_ERROR, "Argument 'loadSheddingModulus' cannot be zero");
    }
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
  private RateLimitRequest createRateLimitRequestForFulfilment(
      Domain domain, Map<String, String> descriptorData) {

    List<LimitDescriptor> descriptors = new ArrayList<>();
    descriptors.add(createLimitDescriptor(DESCRIPTORS_WITH_UPRN, descriptorData));
    if (descriptorData.get(DESC_TEL_NO) != null) {
      descriptors.add(createLimitDescriptor(DESCRIPTORS_WITH_TEL_NO, descriptorData));
    }
    descriptors.add(createLimitDescriptor(DELIVERYCHANNEL_WITH_ONLY_UPRN, descriptorData));
    if (descriptorData.get(DESC_TEL_NO) != null) {
      descriptors.add(createLimitDescriptor(DELIVERYCHANNEL_WITH_ONLY_TEL_NO, descriptorData));
    }
    if (descriptorData.get(DESC_IP_ADDRESS) != null) {
      descriptors.add(createLimitDescriptor(DELIVERYCHANNEL_WITH_ONLY_IP_ADDRESS, descriptorData));
    }

    RateLimitRequest request =
        RateLimitRequest.builder().domain(domain.domainName).descriptors(descriptors).build();
    return request;
  }

  private RateLimitRequest createRateLimitRequestWithAllDescriptors(
      Domain domain, Map<String, String> descriptorData, String[] descriptorNames) {

    List<LimitDescriptor> descriptors = new ArrayList<>();
    descriptors.add(createLimitDescriptor(descriptorNames, descriptorData));

    RateLimitRequest request =
        RateLimitRequest.builder().domain(domain.domainName).descriptors(descriptors).build();
    return request;
  }

  private LimitDescriptor createLimitDescriptor(
      String[] descriptorNames, Map<String, String> descriptorData) {

    List<DescriptorEntry> entries = new ArrayList<>();
    for (String descriptorName : descriptorNames) {
      String descriptorValue = descriptorData.get(descriptorName);
      entries.add(new DescriptorEntry(descriptorName, descriptorValue));
    }

    LimitDescriptor limitDescriptor = new LimitDescriptor();
    limitDescriptor.setEntries(entries);

    return limitDescriptor;
  }

  /**
   * Call the rate limiter using a circuit breaker. This will return without exception if 1) the
   * request is within the rate limits, or 2) the call to the rate limiter fails in some way, or 3)
   * due to previous failures the circuit breaker is 'open'. If the request is above the rate limits
   * then a ResponseStatusException is thrown.
   */
  private void invokeRateLimiter(String requestDescription, RateLimitRequest request) {
    ResponseStatusException limitException =
        circuitBreaker.run(
            () -> {
              try {
                doInvokeRateLimiter(requestDescription, request);
                return null;
              } catch (CTPException e) {
                // we should get here if the rate-limiter is failing or not communicating
                // ... wrap and rethrow to be handled by the circuit-breaker
                throw new RuntimeException(e);
              } catch (ResponseStatusException e) {
                // we have got a 429 but don't rethrow it otherwise this will count against
                // the circuit-breaker accounting, so instead we return it to later throw
                // outside the circuit-breaker mechanism.
                return e;
              }
            },
            throwable -> {
              // This is the Function for the circuitBreaker.run second parameter, which is called
              // when an exception is thrown from the first Supplier parameter (above), including
              // as part of the processing of being in the circuit-breaker OPEN state.
              //
              // It is OK to carry on, since it is better to tolerate limiter error than fail
              // operation, however by getting here, the circuit-breaker has counted the failure,
              // or we are in circuit-breaker OPEN state.
              if (throwable instanceof CallNotPermittedException) {
                log.info("Circuit breaker is OPEN calling rate limiter for " + requestDescription);
              } else {
                log.with("error", throwable.getMessage())
                    .error(throwable, "Rate limiter failure for " + requestDescription);
              }
              return null;
            });

    if (limitException != null) {
      throw limitException;
    }
  }

  /** Make the rest call to the limiter */
  private RateLimitResponse doInvokeRateLimiter(String requestDescription, RateLimitRequest request)
      throws CTPException {
    RateLimitResponse response;
    try {
      response =
          envoyLimiterRestClient.postResource(
              RATE_LIMITER_QUERY_PATH, request, RateLimitResponse.class);

    } catch (ResponseStatusException limiterException) {
      HttpStatus httpStatus = limiterException.getStatus();
      if (httpStatus == HttpStatus.TOO_MANY_REQUESTS) {
        // An expected failure scenario. Record the breach and make sure caller
        // knows by re-throwing the exception
        String breachDescription = describeLimitBreach(request, limiterException);
        log.info(breachDescription);
        throw limiterException;
      } else {
        // Something unexpected went wrong
        log.warn("Limiter request for " + requestDescription + " failed");
        throw new CTPException(
            Fault.SYSTEM_ERROR,
            limiterException,
            "POST request to limiter (for "
                + requestDescription
                + ") failed with http status: "
                + httpStatus.value()
                + "("
                + httpStatus.name()
                + ")");
      }
    }

    return response;
  }

  // Builds a String which lists the LimitDescriptor(s) that triggered a limit breach
  private String describeLimitBreach(
      RateLimitRequest request, ResponseStatusException limiterException) throws CTPException {

    StringBuilder failureDescription = new StringBuilder("Rate limit(s) breached:");
    String responseJson = limiterException.getReason();
    log.with("responseJson", responseJson).debug("Limiter response");
    RateLimitResponse limiterResponse = convertJsonToObject(responseJson);
    for (int i = 0; i < limiterResponse.getStatuses().size(); i++) {
      LimitStatus breachedLimit = limiterResponse.getStatuses().get(i);
      if (breachedLimit.getCode().equals(LimitStatus.CODE_LIMIT_BREACHED)) {
        failureDescription.append(" ");
        failureDescription.append(describeSingleBreach(request, i));
      }
    }

    return failureDescription.toString();
  }

  // Build a string to summarise the limitDescriptor which triggered a limit breach
  private String describeSingleBreach(RateLimitRequest request, int i) {
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
        descriptorKey = "encrypted-" + descriptorKey;
        descriptorValue = encrypt(descriptorValue);
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

  private String encrypt(String telNo) {
    return telNo == null ? null : Encryptor.aesEncrypt(encryptionPassword, telNo);
  }
}
