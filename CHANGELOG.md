# Changelog

All notable changes to AI Pass SDK will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] - 2026-04-18

### Added

- **Automatic token management via OkHttp.** The SDK now installs an
  `AuthorizationInterceptor` that injects `Authorization: Bearer <token>` on
  every request, and a `TokenAuthenticator` that refreshes the token on 401
  responses and retries the original request transparently. Callers no longer
  need to manage tokens or write retry loops.
- `OAuth2Manager.refreshAccessTokenIfNeeded()` — fast-path refresh that only
  hits the network when the stored token is actually expired.

### Changed

- `AiPassSDK.initialize()` is now **idempotent** — repeat calls with the same
  config are no-ops. Safe to call on every cold start without paying for a
  fresh manager + background balance fetch each time.
- `OAuth2Manager.refreshAccessToken()` is now serialized by a `Mutex` so
  concurrent callers share a single refresh attempt (no more refresh
  stampede on burst 401s).
- `AiPassCompletionApiService` and `AiPassAudioApiService` no longer declare
  `@Header("Authorization")` parameters. The interceptor adds the header
  automatically. **Minor breaking change** for callers of the raw Retrofit
  services; the high-level `AiPassSDK.generateCompletion` / `transcribeAudio`
  / `getUserBalance` / `generateSpeech` / `generateImage` signatures are
  unchanged.
- API methods no longer contain manual 401-retry loops — the authenticator
  handles it. A 401 reaching the caller now means refresh itself failed,
  which is surfaced as `ApiResult.Unauthenticated`.

### Removed

- `AiPassSDK.getValidToken()` and `attemptTokenRefresh()` private helpers —
  replaced by the authenticator.
- Internal `retry*` helpers (`retryCompletion`, `retryBalanceFetch`,
  `retrySpeechGeneration`, `retryAudioTranscription`, `retryImageGeneration`)
  — no longer needed.

## [1.0.0] - 2024-11-02

### Added
- Initial public release of AI Pass SDK
- OAuth2 Authorization Code + PKCE flow (RFC 7636 compliant)
- Secure token storage using Android Keystore with AES-256 encryption
- CSRF protection via state parameter validation
- Automatic token refresh support
- Token revocation (logout) functionality
- Generic OAuth2 provider support (works with any RFC 6749 compliant server)
- Type-safe result handling with sealed classes
- Coroutine-based asynchronous operations
- Process death handling (OAuth state persists across app restarts)
- Comprehensive documentation and code samples
- ProGuard/R8 rules for release builds
- BuildConfig support for conditional debug logging

### Security
- All debug logging disabled in release builds
- Encrypted token storage with Android Keystore
- PKCE (Proof Key for Code Exchange) for public clients
- State parameter for CSRF protection
- Secure random generation for PKCE and state
- Token expiry buffer (60 seconds) for clock skew handling

### Documentation
- Comprehensive README with quick start guide
- OAuth2 configuration examples
- Troubleshooting guide
- ProGuard rules documentation
- Apache 2.0 license

## [1.1.0] - 2025-11-02

### Added

- Audio API endpoints support for Text-to-Speech (TTS) and Speech-to-Text (STT)
- New `AiPassAudioApiService` interface with audio endpoints:
    - `POST /audio/speech` - Text-to-Speech generation
    - `POST /v1/audio/speech` - Alternative TTS endpoint
    - `POST /audio/transcriptions` - Speech-to-Text transcription
    - `POST /v1/audio/transcriptions` - Alternative STT endpoint
- Audio data models:
    - `AudioSpeechRequest` for TTS requests (text, model, voice, format, speed)
    - `AudioTranscriptionResponse` for STT responses with text, language, duration, timestamps
    - `Word` and `Segment` models for detailed transcription timestamps
- `AiPassSDK.createAudioApiService()` factory method for direct audio API access
- Extended timeout configuration (60s) for audio processing operations

### Changed

- Updated Java target from 1.8 to 21 for better compatibility with modern Android projects
- Increased read/write timeouts for audio API client to handle larger files
- SDK version bumped from 1.0.0 to 1.1.0

## [Unreleased]

### Planned
- Unit tests for core components
- Integration tests for OAuth2 flow
- Example application demonstrating SDK usage
- CI/CD pipeline with GitHub Actions
- Maven/JitPack publishing support
- Token auto-refresh on API 401 responses
- Multiple simultaneous OAuth flows support

---

## Version History

- **1.2.0** (2026-04-18): Automatic token refresh via OkHttp authenticator, idempotent init, mutex-serialized refresh
- **1.1.0** (2025-11-02): Audio API support (TTS & STT) + Java 21 target
- **1.0.0** (2024-11-02): Initial public release with full OAuth2 + PKCE support
