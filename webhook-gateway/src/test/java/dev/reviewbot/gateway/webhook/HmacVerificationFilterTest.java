package dev.reviewbot.gateway.webhook;

import static dev.reviewbot.gateway.webhook.TestSignatures.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.filter.OrderedFormContentFilter;
import org.springframework.boot.web.servlet.filter.OrderedHiddenHttpMethodFilter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
    properties = {
      "reviewbot.gateway.webhook-secret=" + HmacVerificationFilterTest.SECRET,
      "reviewbot.gateway.max-body-size=1KB"
    })
@AutoConfigureMockMvc
@Import(HmacVerificationFilterTest.HandlerSpy.class)
class HmacVerificationFilterTest {

  static final String SECRET = "It's a Secret to Everybody";
  private static final int CAP = 1024;

  /** Stands in for E3-1: counts how often a request got past the filter and what it read. */
  @RestController
  static class HandlerSpy {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<byte[]> lastBody = new AtomicReference<>();

    @PostMapping("/webhook")
    void webhook(@RequestBody byte[] body) {
      calls.incrementAndGet();
      lastBody.set(body);
    }

    @PostMapping("/settings/ping")
    void settings() {
      calls.incrementAndGet();
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private HandlerSpy spy;
  @Autowired private ApplicationContext context;

  @BeforeEach
  void resetSpy() {
    spy.calls.set(0);
    spy.lastBody.set(null);
  }

  // Multi-byte on purpose: the handler must read the very bytes that were signed.
  @Test
  void validSignaturePassesAndHandlerReadsTheSameBytes() throws Exception {
    byte[] body = "{\"title\":\"héllo — 日本語\"}".getBytes(StandardCharsets.UTF_8);

    mvc.perform(
            post("/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", sign(SECRET, body))
                .content(body))
        .andExpect(status().isOk());

    assertThat(spy.calls).hasValue(1);
    assertThat(spy.lastBody.get()).isEqualTo(body);
  }

  @Test
  void tamperedBodyNeverReachesTheHandler() throws Exception {
    byte[] signed = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
    byte[] sent = "{\"a\":2}".getBytes(StandardCharsets.UTF_8);

    mvc.perform(
            post("/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", sign(SECRET, signed))
                .content(sent))
        .andExpect(status().isUnauthorized());

    assertThat(spy.calls).hasValue(0);
  }

  @Test
  void invalidSignatureNeverReachesTheHandler() throws Exception {
    mvc.perform(
            post("/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", sign("wrong secret", "{}".getBytes(StandardCharsets.UTF_8)))
                .content("{}"))
        .andExpect(status().isUnauthorized());

    assertThat(spy.calls).hasValue(0);
  }

  @Test
  void missingHeaderNeverReachesTheHandler() throws Exception {
    mvc.perform(post("/webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());

    assertThat(spy.calls).hasValue(0);
  }

  @Test
  void wrongLengthHeaderNeverReachesTheHandler() throws Exception {
    mvc.perform(
            post("/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", "sha256=abc")
                .content("{}"))
        .andExpect(status().isUnauthorized());

    assertThat(spy.calls).hasValue(0);
  }

  // A correct signature does not help: the size check comes first, so nothing oversized is buffered.
  @Test
  void bodyOverTheCapGets413AndNeverReachesTheHandler() throws Exception {
    byte[] tooBig = new byte[CAP + 1];

    mvc.perform(
            post("/webhook")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Hub-Signature-256", sign(SECRET, tooBig))
                .content(tooBig))
        .andExpect(status().isPayloadTooLarge());

    assertThat(spy.calls).hasValue(0);
  }

  @Test
  void bodyExactlyAtTheCapIsStillVerifiedAndPassed() throws Exception {
    byte[] atCap = new byte[CAP];

    mvc.perform(
            post("/webhook")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Hub-Signature-256", sign(SECRET, atCap))
                .content(atCap))
        .andExpect(status().isOk());

    assertThat(spy.calls).hasValue(1);
    assertThat(spy.lastBody.get()).hasSize(CAP);
  }

  @Test
  void otherPathsAreNotGatedByTheWebhookFilter() throws Exception {
    mvc.perform(post("/settings/ping")).andExpect(status().isOk());

    assertThat(spy.calls).hasValue(1);
  }

  @Test
  void nonPostOnWebhookIsLeftToMvc() throws Exception {
    mvc.perform(get("/webhook")).andExpect(status().isMethodNotAllowed());
  }

  @Test
  @SuppressWarnings("unchecked")
  void registrationIsScopedToTheWebhookPathAndNeverAFilterBean() {
    FilterRegistrationBean<HmacVerificationFilter> registration =
        context.getBean("hmacVerificationFilter", FilterRegistrationBean.class);

    assertThat(registration.getUrlPatterns()).containsExactly("/webhook");
    assertThat(context.getBeansOfType(HmacVerificationFilter.class)).isEmpty();
  }

  // Everything Spring registers must run after us, except the encoding filter at HIGHEST_PRECEDENCE.
  @Test
  @SuppressWarnings("unchecked")
  void runsAheadOfEveryOtherFilterExceptCharacterEncoding() {
    int ours = context.getBean("hmacVerificationFilter", FilterRegistrationBean.class).getOrder();

    assertThat(ours)
        .isGreaterThan(Ordered.HIGHEST_PRECEDENCE)
        .isLessThan(OrderedHiddenHttpMethodFilter.DEFAULT_ORDER)
        .isLessThan(OrderedFormContentFilter.DEFAULT_ORDER);
    assertThat(otherFilterOrders())
        .isNotEmpty()
        .allSatisfy(
            other ->
                assertThat(other == Ordered.HIGHEST_PRECEDENCE || other > ours)
                    .as("filter order %s must be after ours (%s)", other, ours)
                    .isTrue());
  }

  @SuppressWarnings("rawtypes")
  private List<Integer> otherFilterOrders() {
    List<Integer> orders = new ArrayList<>();
    for (Map.Entry<String, FilterRegistrationBean> e :
        context.getBeansOfType(FilterRegistrationBean.class).entrySet()) {
      if (!e.getKey().equals("hmacVerificationFilter")) {
        orders.add(e.getValue().getOrder());
      }
    }
    for (Filter filter : context.getBeansOfType(Filter.class).values()) {
      orders.add(
          filter instanceof Ordered ordered
              ? ordered.getOrder()
              : OrderUtils.getOrder(filter.getClass(), Ordered.LOWEST_PRECEDENCE));
    }
    return orders;
  }
}
