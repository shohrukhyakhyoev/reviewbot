# reviewbot

AI-powered GitHub PR review bot. Design: docs/spec.md. Backlog: tickets/v1-backlog.md.

## Build

Requires JDK 21. Maven comes from the committed wrapper — no system Maven needed:

```
./mvnw clean verify        # Linux/macOS
.\mvnw.cmd clean verify    # Windows
```

`mvn spotless:apply` fixes formatting violations.

