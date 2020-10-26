package uk.gov.ons.ctp.integration.ratelimiter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LimitStatus {
  public static final String CODE_LIMIT_BREACHED = "OVER_LIMIT";
  public static final String CODE_OK = "OK";

  private String code;
  private CurrentLimit currentLimit;
  private int limitRemaining;
}
