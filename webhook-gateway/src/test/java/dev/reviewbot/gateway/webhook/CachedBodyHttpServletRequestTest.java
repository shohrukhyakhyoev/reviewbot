package dev.reviewbot.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CachedBodyHttpServletRequestTest {

  private static final String TEXT = "héllo — 日本語";
  private static final int CAP = 1024;

  private final MockHttpServletRequest original = new MockHttpServletRequest();

  CachedBodyHttpServletRequestTest() {
    original.setContent(TEXT.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void rawBodyIsTheOriginalBytes() throws IOException {
    CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(original, CAP);

    assertThat(cached.rawBody()).isEqualTo(TEXT.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void streamCanBeReadAgainAfterTheBodyWasCached() throws IOException {
    CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(original, CAP);

    assertThat(cached.getInputStream().readAllBytes()).isEqualTo(cached.rawBody());
    assertThat(cached.getInputStream().readAllBytes()).isEqualTo(cached.rawBody());
  }

  @Test
  void streamReportsFinishedOnlyWhenDrained() throws IOException {
    ServletInputStream stream = new CachedBodyHttpServletRequest(original, CAP).getInputStream();

    assertThat(stream.isReady()).isTrue();
    assertThat(stream.isFinished()).isFalse();
    stream.readAllBytes();
    assertThat(stream.isFinished()).isTrue();
    assertThat(stream.read()).isEqualTo(-1);
  }

  @Test
  void asyncReadListenerIsRejected() throws IOException {
    ServletInputStream stream = new CachedBodyHttpServletRequest(original, CAP).getInputStream();

    assertThatThrownBy(() -> stream.setReadListener(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void readerDefaultsToUtf8WhenNoEncodingIsDeclared() throws IOException {
    CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(original, CAP);

    assertThat(cached.getReader().readLine()).isEqualTo(TEXT);
  }

  @Test
  void readerHonoursTheDeclaredEncoding() throws IOException {
    MockHttpServletRequest latin1 = new MockHttpServletRequest();
    latin1.setCharacterEncoding("ISO-8859-1");
    latin1.setContent("héllo".getBytes(StandardCharsets.ISO_8859_1));

    assertThat(new CachedBodyHttpServletRequest(latin1, CAP).getReader().readLine())
        .isEqualTo("héllo");
  }

  @Test
  void bodyExactlyAtTheCapIsAccepted() throws IOException {
    original.setContent(new byte[CAP]);

    assertThat(new CachedBodyHttpServletRequest(original, CAP).rawBody()).hasSize(CAP);
  }

  @Test
  void bodyOneByteOverTheCapIsRejected() {
    original.setContent(new byte[CAP + 1]);

    assertThatThrownBy(() -> new CachedBodyHttpServletRequest(original, CAP))
        .isInstanceOf(BodyTooLargeException.class);
  }

  @Test
  void declaredLengthOverTheCapIsRejectedWithoutReadingTheBody() {
    MockHttpServletRequest declaresTooMuch =
        new MockHttpServletRequest() {
          @Override
          public long getContentLengthLong() {
            return CAP + 1L;
          }

          @Override
          public ServletInputStream getInputStream() {
            throw new AssertionError("the body must not be read");
          }
        };

    assertThatThrownBy(() -> new CachedBodyHttpServletRequest(declaresTooMuch, CAP))
        .isInstanceOf(BodyTooLargeException.class);
  }

  // Chunked uploads send no Content-Length, so only the bounded read can stop them.
  @Test
  void undeclaredLengthStillBoundsTheRead() {
    MockHttpServletRequest chunked =
        new MockHttpServletRequest() {
          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    chunked.setContent(new byte[CAP + 1]);

    assertThatThrownBy(() -> new CachedBodyHttpServletRequest(chunked, CAP))
        .isInstanceOf(BodyTooLargeException.class);
  }
}
