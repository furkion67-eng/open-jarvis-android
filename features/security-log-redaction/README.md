# Issue #14 — provider and MCP log redaction

This overlay is copied into the extracted Android project by `scripts/repair_sources.py`.

- The shared OkHttp client uses an allowlisted metadata interceptor only; production uses a no-op sink.
- Debug and release use the same variant-independent client builder.
- Native Gemini `:generateContent` and `models` requests put the key in `x-goog-api-key`, never in the URL.
- MCP uses the same safe HTTP client and never prints exception stack traces or raw exception messages.
- Sentinel tests cover URL/query, Authorization header, Turkish prompt, response body and Gemini key placement.

No real API key is used by these tests.
