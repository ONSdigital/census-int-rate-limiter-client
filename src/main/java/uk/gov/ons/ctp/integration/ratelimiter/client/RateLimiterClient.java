package uk.gov.ons.ctp.integration.ratelimiter.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.godaddy.logging.Logger;
import com.godaddy.logging.LoggerFactory;
import java.util.ArrayList;
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
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitRequest;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitResponse;

public class RateLimiterClient {
  private static final Logger log = LoggerFactory.getLogger(RateLimiterClient.class);
  private static final String RATE_LIMITER_QUERY_PATH = "/json";

  private RestClient rateLimiterClient;

  public enum Domain {
    RHSvc("respondenthome");

    private String domainName;

    private Domain(String domainName) {
      this.domainName = domainName;
    }
  }

  public RateLimiterClient(RestClient rateLimiterClient) {
    super();
    this.rateLimiterClient = rateLimiterClient;
  }

  public RateLimitResponse checkRateLimit(
      Domain domain,
      Product product,
      CaseType caseType,
      String ipAddress,
      UniquePropertyReferenceNumber uprn,
      String telNo)
      throws CTPException {
    log.with("domain", domain.domainName).debug("checkRateLimit() calling Rate Limiter Service");

    RateLimitRequest request =
        createRateLimitRequest(domain, product, caseType, ipAddress, uprn, telNo);

    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    String s;
    try {
      s = objectMapper.writeValueAsString(request);
    } catch (JsonProcessingException ex) {
      // TODO Auto-generated catch block
      ex.printStackTrace();
      throw new CTPException(Fault.SYSTEM_ERROR, ex);
    }

    System.out.println("Request as Json: \n" + s);

    RateLimitResponse response;
    try {
      response =
          rateLimiterClient.postResource(
              RATE_LIMITER_QUERY_PATH, request, RateLimitResponse.class, "");
    } catch (ResponseStatusException e) {
      HttpStatus actualHttpStatus = e.getStatus();
      System.out.println("Caught exception: " + actualHttpStatus);
      System.out.println(e.getReason());
      if (actualHttpStatus == HttpStatus.TOO_MANY_REQUESTS) {
        log.info("Rate limit exceeded");
      }
      throw e;
    }

    return response;
  }

  private RateLimitRequest createRateLimitRequest(
      Domain domain,
      Product product,
      CaseType caseType,
      String ipAddress,
      UniquePropertyReferenceNumber uprn,
      String telNo) {
    List<LimitDescriptor> descriptors = new ArrayList<>();
    descriptors.add(createLimitDescriptor(product, caseType, "ipAddress", ipAddress));
    descriptors.add(
        createLimitDescriptor(product, caseType, "uprn", Long.toString(uprn.getValue())));
    if (telNo != null) {
      descriptors.add(createLimitDescriptor(product, caseType, "telNo", telNo));
    }

    RateLimitRequest request =
        RateLimitRequest.builder().domain(domain.domainName).descriptors(descriptors).build();
    return request;
  }

  private LimitDescriptor createLimitDescriptor(
      Product product, CaseType caseType, String entryName, String entryValue) {
    List<DescriptorEntry> entries = new ArrayList<>();
    entries.add(new DescriptorEntry("productGroup", product.getProductGroup().name()));
    entries.add(new DescriptorEntry("individual", product.getIndividual().toString()));
    entries.add(new DescriptorEntry("deliveryChannel", product.getDeliveryChannel().name()));
    entries.add(new DescriptorEntry("caseType", caseType.name()));
    entries.add(new DescriptorEntry(entryName, entryValue));

    LimitDescriptor limitDescriptor = new LimitDescriptor();
    limitDescriptor.setEntries(entries);

    return limitDescriptor;
  }
}
