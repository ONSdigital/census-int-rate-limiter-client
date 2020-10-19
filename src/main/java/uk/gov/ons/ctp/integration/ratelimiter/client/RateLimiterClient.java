package uk.gov.ons.ctp.integration.ratelimiter.client;

import com.godaddy.logging.Logger;
import com.godaddy.logging.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.ons.ctp.common.rest.RestClient;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitRequest;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitResponse;

public class RateLimiterClient {
  private static final Logger log = LoggerFactory.getLogger(RateLimiterClient.class);
  private static final String RATE_LIMITER_QUERY_PATH = "/json";

  private RestClient rateLimiterClient;

  public RateLimiterClient(RestClient rateLimiterClient) {
    super();
    this.rateLimiterClient = rateLimiterClient;
  }

  public RateLimitResponse checkRateLimit(RateLimitRequest rateLimitRequest) {
    log.with("domain", rateLimitRequest.getDomain())
        .debug("checkRateLimit() calling Rate Limiter Service");

    RateLimitResponse rateLimitResponse = null;
    try {
      rateLimitResponse =
          rateLimiterClient.postResource(
              RATE_LIMITER_QUERY_PATH, rateLimitRequest, RateLimitResponse.class);
    } catch (ResponseStatusException ex) {
      if (ex.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
        log.info("Rate limit exceeded");
      }
      throw ex;
    }

    return rateLimitResponse;
  }
}
