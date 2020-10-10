package uk.gov.ons.ctp.integration.ratelimiter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class CurrentLimit {
  private int requestsPerUnit;
  private String unit;
}
