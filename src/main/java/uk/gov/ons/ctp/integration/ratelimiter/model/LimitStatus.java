package uk.gov.ons.ctp.integration.ratelimiter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class LimitStatus {
  private String code;
  private CurrentLimit currentLimit;
  private int limitRemaining;
}
