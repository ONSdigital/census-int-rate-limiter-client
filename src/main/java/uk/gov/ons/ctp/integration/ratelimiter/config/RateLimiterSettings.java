package uk.gov.ons.ctp.integration.ratelimiter.config;

import lombok.Data;
import uk.gov.ons.ctp.common.rest.RestClientConfig;

@Data
public class RateLimiterSettings {
  private String limitRequestPath;
  private RestClientConfig restClientConfig;
}
