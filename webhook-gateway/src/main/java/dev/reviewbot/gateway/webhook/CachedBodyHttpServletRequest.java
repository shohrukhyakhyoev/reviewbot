package dev.reviewbot.gateway.webhook;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/** Reads the body once so the exact bytes can be HMAC'd and then parsed downstream. */
public final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

  private final byte[] body;

  public CachedBodyHttpServletRequest(HttpServletRequest request, int maxBytes)
      throws IOException {
    super(request);
    // Content-Length may be absent (chunked) or wrong, so the read itself is bounded too.
    if (request.getContentLengthLong() > maxBytes) {
      throw new BodyTooLargeException(maxBytes);
    }
    byte[] bytes = request.getInputStream().readNBytes(maxBytes + 1);
    if (bytes.length > maxBytes) {
      throw new BodyTooLargeException(maxBytes);
    }
    this.body = bytes;
  }

  public byte[] rawBody() {
    return body;
  }

  @Override
  public ServletInputStream getInputStream() {
    ByteArrayInputStream source = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      @Override
      public int read() {
        return source.read();
      }

      @Override
      public int read(byte[] b, int off, int len) {
        return source.read(b, off, len);
      }

      @Override
      public boolean isFinished() {
        return source.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener listener) {
        throw new UnsupportedOperationException("async reads are not supported");
      }
    };
  }

  @Override
  public BufferedReader getReader() {
    String name = getCharacterEncoding();
    Charset charset = name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
    return new BufferedReader(new InputStreamReader(getInputStream(), charset));
  }
}
