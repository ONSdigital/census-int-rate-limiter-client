package uk.gov.ons.ctp.integration.ratelimiter.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RateLimitResponse {
  private String overallCode;
  private List<LimitStatus> statuses;
}
