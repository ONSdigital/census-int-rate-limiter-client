package uk.gov.ons.ctp.integration.ratelimiter.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.ons.ctp.common.FixtureHelper;
import uk.gov.ons.ctp.common.rest.RestClient;
import uk.gov.ons.ctp.integration.ratelimiter.client.RateLimiterClient.Domain;
import uk.gov.ons.ctp.integration.ratelimiter.model.DescriptorEntry;
import uk.gov.ons.ctp.integration.ratelimiter.model.LimitDescriptor;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitRequest;
import uk.gov.ons.ctp.integration.ratelimiter.model.RateLimitResponse;

public abstract class RateLimiterClientTestBase {
  static final String AN_IPv4_ADDRESS = "123.111.222.23";
  static final String ENCRYPT_PASSWORD = "password";

  @Mock RestClient restClient;
  @Mock CircuitBreaker circuitBreaker;
  @Mock CallNotPermittedException circuitBreakerOpenException;
  RateLimiterClient rateLimiterClient;

  Domain domain = RateLimiterClient.Domain.RH;

  private void simulateCircuitBreaker() {
    doAnswer(
            new Answer<Object>() {
              @SuppressWarnings("unchecked")
              @Override
              public Object answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                Supplier<Object> runner = (Supplier<Object>) args[0];
                Function<Throwable, Object> fallback = (Function<Throwable, Object>) args[1];

                try {
                  // execute the circuitBreaker.run first argument (the Supplier for the code you
                  // want to run)
                  return runner.get();
                } catch (Throwable t) {
                  // execute the circuitBreaker.run second argument (the fallback Function)
                  fallback.apply(t);
                }
                return null;
              }
            })
        .when(circuitBreaker)
        .run(any(), any());
  }

  @Before
  public void setUp() {
    rateLimiterClient = new RateLimiterClient(restClient, circuitBreaker, ENCRYPT_PASSWORD);
    simulateCircuitBreaker();
  }

  @Captor ArgumentCaptor<RateLimitRequest> limitRequestCaptor;

  RateLimitRequest verifiedRequestSentToLimiter() {
    verify(restClient).postResource(any(), limitRequestCaptor.capture(), any(), any());
    return limitRequestCaptor.getValue();
  }

  void verifyEnvoyLimiterNotCalled() {
    verify(restClient, never()).postResource(any(), limitRequestCaptor.capture(), any(), any());
  }

  void verifyEntry(
      LimitDescriptor descriptor, int index, String expectedKey, String expectedValue) {
    DescriptorEntry entry = descriptor.getEntries().get(index);
    assertEquals(expectedKey, entry.getKey());
    assertEquals(expectedValue, entry.getValue());
  }

  private RateLimitResponse exampleRateLimitResponse() {
    return FixtureHelper.loadPackageFixtures(RateLimitResponse[].class).get(0);
  }

  ResponseStatusException overTheLimitException(RateLimitResponse resp) throws Exception {
    String tooManyRequestsString = new ObjectMapper().writeValueAsString(resp);
    return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, tooManyRequestsString, null);
  }

  ResponseStatusException overTheLimitException() throws Exception {
    return overTheLimitException(exampleRateLimitResponse());
  }

  ResponseStatusException badRequestException() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad request", null);
  }

  ResponseStatusException corruptedJsonException() {
    String corruptedJson = "aoeu<.p#$%^EOUAEOU3245";
    return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, corruptedJson, null);
  }

  void mockRateLimitException(Throwable t) {
    when(restClient.postResource(eq("/json"), any(), eq(RateLimitResponse.class))).thenThrow(t);
  }
}
