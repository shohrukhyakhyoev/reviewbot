package dev.reviewbot.gateway.webhook;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/** Trust boundary for {@code POST /webhook}: nothing downstream sees an unverified body. */
public final class HmacVerificationFilter extends OncePerRequestFilter {

  private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

  private final SignatureVerifier verifier;
  private final int maxBodyBytes;

  public HmacVerificationFilter(SignatureVerifier verifier, int maxBodyBytes) {
    this.verifier = verifier;
    this.maxBodyBytes = maxBodyBytes;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    CachedBodyHttpServletRequest cached;
    try {
      cached = new CachedBodyHttpServletRequest(request, maxBodyBytes);
    } catch (BodyTooLargeException e) {
      response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      return;
    }
    if (!verifier.verify(cached.rawBody(), cached.getHeader(SIGNATURE_HEADER))) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    chain.doFilter(cached, response);
  }
}
