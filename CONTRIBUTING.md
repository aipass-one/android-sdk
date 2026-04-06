# Contributing to AI Pass SDK

Thank you for your interest in contributing to AI Pass SDK! This document provides guidelines and instructions for contributing.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Making Changes](#making-changes)
- [Testing](#testing)
- [Submitting Changes](#submitting-changes)
- [Code Style](#code-style)
- [Commit Messages](#commit-messages)
- [Pull Request Process](#pull-request-process)

## Code of Conduct

This project adheres to a code of conduct that all contributors are expected to follow:

- Be respectful and inclusive
- Welcome newcomers and help them learn
- Focus on constructive feedback
- Respect differing viewpoints and experiences
- Show empathy towards other community members

## Getting Started

1. **Fork the repository** on GitHub
2. **Clone your fork** locally:
   ```bash
   git clone https://github.com/your-username/PersonalAccountant.git
   cd PersonalAccountant
   ```
3. **Add the upstream repository**:
   ```bash
   git remote add upstream https://github.com/original-repo/PersonalAccountant.git
   ```

## Development Setup

### Prerequisites

- Android Studio Hedgehog | 2023.1.1 or later
- JDK 17 or later
- Android SDK with API 35 (Android 15)
- Kotlin 1.9+

### Build the SDK

```bash
./gradlew :ai-pass-sdk:build
```

### Run Tests

```bash
# Unit tests
./gradlew :ai-pass-sdk:testDebugUnitTest

# Lint check
./gradlew :ai-pass-sdk:lintDebug
```

## Making Changes

1. **Create a new branch** for your changes:
   ```bash
   git checkout -b feature/your-feature-name
   ```

   Branch naming conventions:
   - `feature/` - New features
   - `fix/` - Bug fixes
   - `docs/` - Documentation changes
   - `refactor/` - Code refactoring
   - `test/` - Adding or updating tests

2. **Keep your branch up to date** with upstream:
   ```bash
   git fetch upstream
   git rebase upstream/main
   ```

3. **Make your changes** following our [code style guidelines](#code-style)

4. **Write or update tests** for your changes

5. **Update documentation** as needed

## Testing

All changes must include appropriate tests:

### Writing Tests

- Place unit tests in `src/test/java/one/aipass/`
- Use JUnit 4 and Robolectric for Android tests
- Follow the existing test structure and naming conventions
- Aim for high code coverage (minimum 80%)

### Running Tests

```bash
# Run all tests
./gradlew :ai-pass-sdk:testDebugUnitTest

# Run specific test class
./gradlew :ai-pass-sdk:testDebugUnitTest --tests "one.aipass.PkceGeneratorTest"

# Run with coverage
./gradlew :ai-pass-sdk:testDebugUnitTestCoverage
```

### Test Requirements

- All public APIs must have unit tests
- Security-critical code (PKCE, token storage) requires comprehensive tests
- Tests must pass in CI before merge
- No test should be marked as `@Ignore` without a valid reason

## Submitting Changes

1. **Commit your changes** with clear, descriptive commit messages (see [Commit Messages](#commit-messages))

2. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```

3. **Create a Pull Request** on GitHub

4. **Wait for review** - a maintainer will review your PR and may request changes

## Code Style

### Kotlin Style Guide

We follow the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html) with these additions:

#### Naming Conventions

- Classes: `PascalCase`
- Functions: `camelCase`
- Properties: `camelCase`
- Constants: `UPPER_SNAKE_CASE`
- Private properties: `_camelCase` (with leading underscore) if shadowing is needed

#### Code Organization

```kotlin
class Example {
    // Companion object first
    companion object {
        private const val TAG = "Example"
    }

    // Properties
    private val property: String

    // Init blocks
    init {
        // ...
    }

    // Public methods
    fun publicMethod() {
        // ...
    }

    // Private methods
    private fun privateMethod() {
        // ...
    }
}
```

#### Documentation

- All public APIs must have KDoc comments
- Include parameter descriptions and return value documentation
- Provide usage examples for complex APIs
- Document exceptions that may be thrown

```kotlin
/**
 * Generates a PKCE code pair for OAuth2 authorization
 *
 * This method creates a cryptographically random code verifier
 * and its corresponding SHA-256 challenge as per RFC 7636.
 *
 * Usage:
 * ```
 * val pkce = PkceGenerator.generate()
 * // Use pkce.codeChallenge in authorization request
 * // Use pkce.codeVerifier in token request
 * ```
 *
 * @return PkceCodePair containing verifier and challenge
 */
fun generate(): PkceCodePair {
    // ...
}
```

#### Error Handling

- Use sealed classes for result types
- Provide descriptive error messages
- Log errors appropriately (DEBUG checks for sensitive data)
- Never swallow exceptions silently

### Android-Specific Guidelines

- Minimum SDK: 21 (Android 5.0)
- Target SDK: 35 (Android 15)
- Use AndroidX libraries (not support libraries)
- Follow Android Architecture Components best practices
- Avoid hardcoded strings (use resources when applicable)

### Security Guidelines

- **Never log sensitive data** in production
- Use `BuildConfig.DEBUG` checks for all debug logging
- Encrypt all tokens using Android Keystore
- Follow OWASP Mobile Security guidelines
- Use PKCE for all OAuth2 flows
- Validate all inputs from external sources

## Commit Messages

Follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```
<type>(<scope>): <subject>

<body>

<footer>
```

### Types

- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, missing semicolons, etc.)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Maintenance tasks

### Examples

```
feat(oauth): add token auto-refresh support

Implement automatic token refresh when access token expires.
Uses refresh token to obtain new access token transparently.

Closes #123
```

```
fix(pkce): ensure code verifier meets RFC 7636 length requirements

Code verifier was sometimes generated shorter than 43 characters.
Updated to always generate exactly 128 characters as per RFC 7636.

Fixes #456
```

```
docs(readme): update installation instructions

Add missing ProGuard configuration step and clarify
custom URL scheme setup in AndroidManifest.
```

## Pull Request Process

1. **Ensure your PR**:
   - Has a clear title and description
   - References any related issues
   - Includes tests for new features
   - Updates documentation as needed
   - Passes all CI checks

2. **PR Description Template**:
   ```markdown
   ## Description
   Brief description of what this PR does

   ## Type of Change
   - [ ] Bug fix
   - [ ] New feature
   - [ ] Breaking change
   - [ ] Documentation update

   ## Checklist
   - [ ] Tests added/updated
   - [ ] Documentation updated
   - [ ] Code follows style guidelines
   - [ ] All tests pass
   - [ ] No new warnings

   ## Related Issues
   Closes #issue_number
   ```

3. **Review Process**:
   - Maintainers will review your PR within 2-3 business days
   - Address any requested changes promptly
   - Be open to feedback and suggestions
   - Once approved, a maintainer will merge your PR

4. **After Merge**:
   - Your contribution will be included in the next release
   - You'll be credited in the CHANGELOG
   - Delete your feature branch

## Development Workflow

### Feature Development

1. Create feature branch from `main`
2. Implement feature with tests
3. Update documentation
4. Submit PR to `main`
5. Address review comments
6. Merge after approval

### Bug Fixes

1. Create fix branch from `main` (or release branch for hotfixes)
2. Write failing test demonstrating the bug
3. Fix the bug
4. Ensure test passes
5. Submit PR
6. Merge after approval

### Security Fixes

For security vulnerabilities:
1. **Do not create a public issue**
2. Email security concerns privately to the maintainers
3. Work with maintainers to develop and test fix
4. Coordinate disclosure timeline
5. Release security patch

## Questions?

If you have questions about contributing:
- Check existing issues and discussions
- Open a new issue with the `question` label
- Contact the maintainers

## License

By contributing to AI Pass SDK, you agree that your contributions will be licensed under the Apache License 2.0.

---

Thank you for contributing to AI Pass SDK! 🎉
