package uk.gov.ons.ctp.integration.ratelimiter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DescriptorEntry {
  public static final String URPN_KEY = "uprn";
  public static final String TELNO_KEY = "telNo";
  public static final String IPADDRESS_KEY = "ipAddress";

  private String key;
  private String value;
}
