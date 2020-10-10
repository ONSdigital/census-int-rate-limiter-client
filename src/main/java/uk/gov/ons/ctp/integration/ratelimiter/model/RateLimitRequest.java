package uk.gov.ons.ctp.integration.ratelimiter.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class RateLimitRequest {
  private String domain;
  private List<LimitDescriptor> descriptors; 
}
