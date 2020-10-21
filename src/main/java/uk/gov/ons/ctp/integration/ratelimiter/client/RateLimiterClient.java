package uk.gov.ons.ctp.integration.ratelimiter.client;

import java.util.Optional;
import com.godaddy.logging.Logger;
import com.godaddy.logging.LoggerFactory;
import uk.gov.ons.ctp.common.domain.CaseType;
import uk.gov.ons.ctp.common.domain.UniquePropertyReferenceNumber;
import uk.gov.ons.ctp.common.rest.RestClient;
import uk.gov.ons.ctp.integration.common.product.model.Product;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitResponse;

public class RateLimiterClient {
  private static final Logger log = LoggerFactory.getLogger(RateLimiterClient.class);
  private static final String RATE_LIMITER_QUERY_PATH = "/json";

  private RestClient rateLimiterClient;

  public RateLimiterClient(RestClient rateLimiterClient) {
    super();
    this.rateLimiterClient = rateLimiterClient;
  }

  public RateLimitResponse checkRateLimit(String domain, Product product,
      CaseType caseType,
      String ipAddress,
      UniquePropertyReferenceNumber uprn,
      Optional<String> telNo) {
    log.with("domain", domain)
        .debug("checkRateLimit() calling Rate Limiter Service");

//    RateLimitResponse rateLimitResponse = null;
//    try {
//      rateLimitResponse =
//          rateLimiterClient.postResource(
//              RATE_LIMITER_QUERY_PATH, rateLimitRequest, RateLimitResponse.class);
//    } catch (ResponseStatusException ex) {
//      if (ex.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
//        log.info("Rate limit exceeded");
//      }
//      throw ex;
//    }
//
//    return rateLimitResponse;
    
    return null;
  }
}
