/// AI Pass SDK for Flutter
/// Complete OAuth2 + AI API client in a single file
///
/// Usage:
/// ```dart
/// // 1. Initialize
/// await AiPassSDK.initialize(
///   clientId: 'your_client_id',
///   redirectUri: 'myapp://callback',
/// );
///
/// // 2. Login
/// await AiPassSDK.login();
///
/// // 3. Use AI features
/// final response = await AiPassSDK.generateCompletion('What is 2+2?');
/// final balance = await AiPassSDK.getUserBalance();
/// final receipt = await AiPassSDK.analyzeReceipt(imageBase64);
/// ```

library aipass_sdk;

import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';

// ============================================================================
// MAIN SDK CLASS
// ============================================================================

class AiPassSDK {
  static const String _defaultBaseUrl = 'https://aipass.one';
  static const List<String> _defaultScopes = ['api:access', 'profile:read'];

  static String? _clientId;
  static String? _clientSecret;
  static String? _redirectUri;
  static String _baseUrl = _defaultBaseUrl;
  static List<String> _scopes = _defaultScopes;

  static String? _accessToken;
  static String? _refreshToken;
  static DateTime? _tokenExpiry;

  static String? _pendingCodeVerifier;
  static String? _pendingState;

  static bool _isInitialized = false;

  /// Initialize the SDK
  static Future<void> initialize({
    required String clientId,
    required String redirectUri,
    String? clientSecret,
    String baseUrl = _defaultBaseUrl,
    List<String>? scopes,
  }) async {
    _clientId = clientId;
    _clientSecret = clientSecret;
    _redirectUri = redirectUri;
    _baseUrl = baseUrl;
    _scopes = scopes ?? _defaultScopes;
    _isInitialized = true;

    // Load saved tokens
    await _loadTokens();
  }

  /// Check if SDK is initialized
  static bool get isInitialized => _isInitialized;

  /// Check if user is authenticated
  static bool get isAuthenticated => _accessToken != null && !_isTokenExpired();

  /// Get current access token (auto-refresh if needed)
  static Future<String?> getAccessToken() async {
    _ensureInitialized();

    if (_accessToken == null) return null;

    // Auto-refresh if token expires within 5 minutes
    if (_isTokenExpired() || _willExpireSoon()) {
      final refreshed = await refreshAccessToken();
      if (!refreshed) return null;
    }

    return _accessToken;
  }

  // ==========================================================================
  // OAUTH2 FLOW
  // ==========================================================================

  /// Start OAuth2 login flow
  static Future<void> login() async {
    _ensureInitialized();

    // Generate PKCE
    _pendingCodeVerifier = _generateCodeVerifier();
    final codeChallenge = _generateCodeChallenge(_pendingCodeVerifier!);
    _pendingState = _generateState();

    // Save pending state
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('pending_verifier', _pendingCodeVerifier!);
    await prefs.setString('pending_state', _pendingState!);

    // Build authorization URL
    final authUrl = Uri.parse('$_baseUrl/oauth2/authorize').replace(
      queryParameters: {
        'client_id': _clientId!,
        'response_type': 'code',
        'redirect_uri': _redirectUri!,
        'code_challenge': codeChallenge,
        'code_challenge_method': 'S256',
        'state': _pendingState!,
        'scope': _scopes.join(' '),
      },
    );

    // Launch browser
    if (await canLaunchUrl(authUrl)) {
      await launchUrl(authUrl, mode: LaunchMode.externalApplication);
    } else {
      throw AiPassException('Could not launch authorization URL');
    }
  }

  /// Handle OAuth callback (call this from your deep link handler)
  static Future<OAuth2Result> handleCallback(Uri callbackUri) async {
    _ensureInitialized();

    try {
      // Restore pending state if needed
      if (_pendingState == null || _pendingCodeVerifier == null) {
        final prefs = await SharedPreferences.getInstance();
        _pendingState = prefs.getString('pending_state');
        _pendingCodeVerifier = prefs.getString('pending_verifier');
      }

      // Check for error
      final error = callbackUri.queryParameters['error'];
      if (error != null) {
        final errorDesc = callbackUri.queryParameters['error_description'];
        return OAuth2Result.error(error, errorDesc);
      }

      // Validate state (CSRF protection)
      final returnedState = callbackUri.queryParameters['state'];
      if (returnedState != _pendingState) {
        return OAuth2Result.error('invalid_state', 'State mismatch');
      }

      // Get authorization code
      final code = callbackUri.queryParameters['code'];
      if (code == null) {
        return OAuth2Result.error('invalid_callback', 'No authorization code');
      }

      // Exchange code for token
      final response = await http.post(
        Uri.parse('$_baseUrl/oauth2/token'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({
          'grantType': 'authorization_code',
          'code': code,
          'redirectUri': _redirectUri,
          'clientId': _clientId,
          'codeVerifier': _pendingCodeVerifier,
          if (_clientSecret != null) 'clientSecret': _clientSecret,
        }),
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        await _saveTokens(
          accessToken: data['access_token'],
          refreshToken: data['refresh_token'],
          expiresIn: data['expires_in'],
        );

        // Clear pending state
        final prefs = await SharedPreferences.getInstance();
        await prefs.remove('pending_verifier');
        await prefs.remove('pending_state');

        return OAuth2Result.success(data['access_token']);
      } else {
        final error = jsonDecode(response.body);
        return OAuth2Result.error(
          error['error'] ?? 'token_exchange_failed',
          error['error_description'],
        );
      }
    } catch (e) {
      return OAuth2Result.error('client_error', e.toString());
    }
  }

  /// Refresh access token
  static Future<bool> refreshAccessToken() async {
    _ensureInitialized();

    if (_refreshToken == null) return false;

    try {
      final response = await http.post(
        Uri.parse('$_baseUrl/oauth2/token'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({
          'grantType': 'refresh_token',
          'refreshToken': _refreshToken,
          'clientId': _clientId,
          if (_clientSecret != null) 'clientSecret': _clientSecret,
        }),
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        await _saveTokens(
          accessToken: data['access_token'],
          refreshToken: data['refresh_token'] ?? _refreshToken,
          expiresIn: data['expires_in'],
        );
        return true;
      }

      return false;
    } catch (e) {
      return false;
    }
  }

  /// Logout (revoke token and clear storage)
  static Future<bool> logout() async {
    _ensureInitialized();

    try {
      if (_accessToken != null) {
        // Try to revoke token on server
        await http.post(
          Uri.parse('$_baseUrl/oauth2/revoke'),
          headers: {'Content-Type': 'application/json'},
          body: jsonEncode({'token': _accessToken}),
        );
      }
    } catch (e) {
      // Ignore revocation errors, still clear local tokens
    }

    // Clear local tokens
    await _clearTokens();
    return true;
  }

  // ==========================================================================
  // CHAT COMPLETIONS
  // ==========================================================================

  /// Generate AI completion (text-only)
  static Future<String> generateCompletion(
    String prompt, {
    String model = 'gemini/gemini-2.5-flash-lite',
    double temperature = 0.7,
    int maxTokens = 1000,
    String? systemPrompt,
  }) async {
    final messages = <Map<String, dynamic>>[];
    if (systemPrompt != null) {
      messages.add({'role': 'system', 'content': systemPrompt});
    }
    messages.add({'role': 'user', 'content': prompt});

    final response = await _apiCall(
      'POST',
      '/oauth2/v1/chat/completions',
      body: {
        'model': model,
        'messages': messages,
        'temperature': temperature,
        'max_tokens': maxTokens,
        'stream': false,
      },
    );

    return response['choices'][0]['message']['content'];
  }

  /// Generate AI completion with chat history
  static Future<String> chat(
    List<ChatMessage> messages, {
    String model = 'gemini/gemini-2.5-flash-lite',
    double temperature = 0.7,
    int maxTokens = 1000,
  }) async {
    final response = await _apiCall(
      'POST',
      '/oauth2/v1/chat/completions',
      body: {
        'model': model,
        'messages': messages.map((m) => m.toJson()).toList(),
        'temperature': temperature,
        'max_tokens': maxTokens,
        'stream': false,
      },
    );

    return response['choices'][0]['message']['content'];
  }

  // ==========================================================================
  // VISION API
  // ==========================================================================

  /// Analyze image (multimodal)
  static Future<String> analyzeImage(
    String imageBase64, {
    required String prompt,
    String model = 'gemini/gemini-2.5-flash-lite',
    int maxTokens = 2000,
  }) async {
    final response = await _apiCall(
      'POST',
      '/oauth2/v1/chat/completions',
      body: {
        'model': model,
        'messages': [
          {
            'role': 'user',
            'content': [
              {'type': 'text', 'text': prompt},
              {
                'type': 'image_url',
                'image_url': {'url': 'data:image/jpeg;base64,$imageBase64'}
              }
            ]
          }
        ],
        'max_tokens': maxTokens,
      },
    );

    return response['choices'][0]['message']['content'];
  }

  /// Analyze receipt image
  static Future<ReceiptData> analyzeReceipt(
    String imageBase64, {
    String model = 'gemini/gemini-2.5-flash-lite',
  }) async {
    final prompt = '''
Extract receipt data in JSON format:
{
  "amount": total amount as number,
  "currency": currency code (e.g., USD, EUR),
  "date": date in YYYY-MM-DD format,
  "merchant": merchant/store name,
  "category": expense category,
  "confidence": confidence level from 0.0 to 1.0
}
''';

    final response = await analyzeImage(
      imageBase64,
      prompt: prompt,
      model: model,
      maxTokens: 2000,
    );

    // Extract JSON from response
    final jsonMatch = RegExp(r'\{[\s\S]*\}').firstMatch(response);
    if (jsonMatch != null) {
      final data = jsonDecode(jsonMatch.group(0)!);
      return ReceiptData.fromJson(data);
    }

    throw AiPassException('Failed to parse receipt data');
  }

  // ==========================================================================
  // AUDIO API
  // ==========================================================================

  /// Text-to-Speech
  static Future<File> generateSpeech(
    String text, {
    String model = 'tts-1',
    String voice = 'alloy',
    String format = 'mp3',
    double speed = 1.0,
  }) async {
    final token = await getAccessToken();
    if (token == null) throw AiPassException('Not authenticated');

    final response = await http.post(
      Uri.parse('$_baseUrl/oauth2/v1/audio/speech'),
      headers: {
        'Authorization': 'Bearer $token',
        'Content-Type': 'application/json',
      },
      body: jsonEncode({
        'model': model,
        'input': text,
        'voice': voice,
        'response_format': format,
        'speed': speed,
      }),
    );

    if (response.statusCode == 200) {
      final tempDir = Directory.systemTemp;
      final file = File('${tempDir.path}/tts_${DateTime.now().millisecondsSinceEpoch}.$format');
      await file.writeAsBytes(response.bodyBytes);
      return file;
    }

    throw AiPassException('TTS failed: ${response.statusCode}');
  }

  /// Speech-to-Text
  static Future<String> transcribeAudio(
    File audioFile, {
    String model = 'whisper-1',
    String? language,
    String? prompt,
  }) async {
    final token = await getAccessToken();
    if (token == null) throw AiPassException('Not authenticated');

    final request = http.MultipartRequest(
      'POST',
      Uri.parse('$_baseUrl/oauth2/v1/audio/transcriptions'),
    );

    request.headers['Authorization'] = 'Bearer $token';
    request.files.add(await http.MultipartFile.fromPath('file', audioFile.path));
    request.fields['model'] = model;
    if (language != null) request.fields['language'] = language;
    if (prompt != null) request.fields['prompt'] = prompt;
    request.fields['response_format'] = 'json';

    final streamedResponse = await request.send();
    final response = await http.Response.fromStream(streamedResponse);

    if (response.statusCode == 200) {
      final data = jsonDecode(response.body);
      return data['text'];
    }

    throw AiPassException('STT failed: ${response.statusCode}');
  }

  // ==========================================================================
  // EMBEDDINGS
  // ==========================================================================

  /// Generate text embeddings
  static Future<List<double>> generateEmbeddings(
    String text, {
    String model = 'text-embedding-3-small',
  }) async {
    final response = await _apiCall(
      'POST',
      '/oauth2/v1/embeddings',
      body: {
        'model': model,
        'input': text,
        'encoding_format': 'float',
      },
    );

    return List<double>.from(response['data'][0]['embedding']);
  }

  // ==========================================================================
  // IMAGES
  // ==========================================================================

  /// Generate image
  static Future<String> generateImage(
    String prompt, {
    String model = 'dall-e-3',
    int n = 1,
    String size = '1024x1024',
  }) async {
    final response = await _apiCall(
      'POST',
      '/oauth2/v1/images/generations',
      body: {
        'model': model,
        'prompt': prompt,
        'n': n,
        'size': size,
      },
    );

    return response['data'][0]['url'];
  }

  // ==========================================================================
  // MODELS
  // ==========================================================================

  /// List available models
  static Future<List<String>> listModels() async {
    final response = await _apiCall('GET', '/oauth2/v1/models');
    return List<String>.from(
      response['data'].map((m) => m['id']),
    );
  }

  /// Get model info
  static Future<Map<String, dynamic>> getModelInfo(String modelId) async {
    return await _apiCall('GET', '/oauth2/v1/models/$modelId');
  }

  // ==========================================================================
  // USER & USAGE
  // ==========================================================================

  /// Get user profile
  static Future<UserProfile> getUserProfile() async {
    final response = await _apiCall('GET', '/oauth2/userinfo');
    return UserProfile.fromJson(response);
  }

  /// Get usage and balance
  static Future<UsageBalance> getUserBalance() async {
    final response = await _apiCall('GET', '/api/v1/usage/me/summary');
    return UsageBalance.fromJson(response['data']);
  }

  // ==========================================================================
  // PRIVATE HELPERS
  // ==========================================================================

  static void _ensureInitialized() {
    if (!_isInitialized) {
      throw AiPassException('SDK not initialized. Call AiPassSDK.initialize() first.');
    }
  }

  static bool _isTokenExpired() {
    if (_tokenExpiry == null) return true;
    return DateTime.now().isAfter(_tokenExpiry!);
  }

  static bool _willExpireSoon() {
    if (_tokenExpiry == null) return true;
    final fiveMinutesFromNow = DateTime.now().add(Duration(minutes: 5));
    return fiveMinutesFromNow.isAfter(_tokenExpiry!);
  }

  static String _generateCodeVerifier() {
    final random = Random.secure();
    final values = List<int>.generate(32, (i) => random.nextInt(256));
    return base64UrlEncode(values).replaceAll('=', '');
  }

  static String _generateCodeChallenge(String verifier) {
    final bytes = utf8.encode(verifier);
    final digest = sha256.convert(bytes);
    return base64UrlEncode(digest.bytes).replaceAll('=', '');
  }

  static String _generateState() {
    final random = Random.secure();
    final values = List<int>.generate(32, (i) => random.nextInt(256));
    return base64UrlEncode(values).replaceAll('=', '');
  }

  static Future<void> _saveTokens({
    required String accessToken,
    String? refreshToken,
    required int expiresIn,
  }) async {
    _accessToken = accessToken;
    _refreshToken = refreshToken;
    _tokenExpiry = DateTime.now().add(Duration(seconds: expiresIn));

    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('access_token', accessToken);
    if (refreshToken != null) {
      await prefs.setString('refresh_token', refreshToken);
    }
    await prefs.setString('token_expiry', _tokenExpiry!.toIso8601String());
  }

  static Future<void> _loadTokens() async {
    final prefs = await SharedPreferences.getInstance();
    _accessToken = prefs.getString('access_token');
    _refreshToken = prefs.getString('refresh_token');
    final expiryStr = prefs.getString('token_expiry');
    if (expiryStr != null) {
      _tokenExpiry = DateTime.parse(expiryStr);
    }
  }

  static Future<void> _clearTokens() async {
    _accessToken = null;
    _refreshToken = null;
    _tokenExpiry = null;

    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('access_token');
    await prefs.remove('refresh_token');
    await prefs.remove('token_expiry');
  }

  static Future<Map<String, dynamic>> _apiCall(
    String method,
    String path, {
    Map<String, dynamic>? body,
    int retries = 1,
  }) async {
    _ensureInitialized();

    final token = await getAccessToken();
    if (token == null) throw AiPassException('Not authenticated');

    final url = Uri.parse('$_baseUrl$path');
    http.Response response;

    for (int i = 0; i <= retries; i++) {
      try {
        if (method == 'GET') {
          response = await http.get(
            url,
            headers: {'Authorization': 'Bearer $token'},
          );
        } else {
          response = await http.post(
            url,
            headers: {
              'Authorization': 'Bearer $token',
              'Content-Type': 'application/json',
            },
            body: body != null ? jsonEncode(body) : null,
          );
        }

        // Success
        if (response.statusCode == 200) {
          return jsonDecode(response.body);
        }

        // Token expired - refresh and retry
        if (response.statusCode == 401 && i < retries) {
          final refreshed = await refreshAccessToken();
          if (refreshed) continue;
        }

        // Rate limit or server error - retry with backoff
        if (response.statusCode >= 429 && i < retries) {
          await Future.delayed(Duration(seconds: pow(2, i).toInt()));
          continue;
        }

        // Other errors
        throw AiPassException('API error: ${response.statusCode} ${response.body}');
      } catch (e) {
        if (i == retries) rethrow;
      }
    }

    throw AiPassException('API call failed after retries');
  }
}

// ============================================================================
// DATA MODELS
// ============================================================================

/// Chat message
class ChatMessage {
  final String role;
  final dynamic content;

  ChatMessage({
    required this.role,
    required this.content,
  });

  ChatMessage.system(String text) : this(role: 'system', content: text);
  ChatMessage.user(String text) : this(role: 'user', content: text);
  ChatMessage.assistant(String text) : this(role: 'assistant', content: text);

  Map<String, dynamic> toJson() => {
        'role': role,
        'content': content,
      };
}

/// Receipt analysis result
class ReceiptData {
  final double? amount;
  final String? currency;
  final String? date;
  final String? merchant;
  final String? category;
  final double confidence;

  ReceiptData({
    this.amount,
    this.currency,
    this.date,
    this.merchant,
    this.category,
    this.confidence = 0.0,
  });

  factory ReceiptData.fromJson(Map<String, dynamic> json) {
    return ReceiptData(
      amount: (json['amount'] as num?)?.toDouble(),
      currency: json['currency'],
      date: json['date'],
      merchant: json['merchant'],
      category: json['category'],
      confidence: (json['confidence'] as num?)?.toDouble() ?? 0.0,
    );
  }
}

/// User profile
class UserProfile {
  final String sub;
  final String? email;
  final String? name;

  UserProfile({
    required this.sub,
    this.email,
    this.name,
  });

  factory UserProfile.fromJson(Map<String, dynamic> json) {
    return UserProfile(
      sub: json['sub'],
      email: json['email'],
      name: json['name'],
    );
  }
}

/// Usage and balance
class UsageBalance {
  final double totalCost;
  final double maxBudget;
  final double remainingBudget;

  UsageBalance({
    required this.totalCost,
    required this.maxBudget,
    required this.remainingBudget,
  });

  factory UsageBalance.fromJson(Map<String, dynamic> json) {
    return UsageBalance(
      totalCost: (json['totalCost'] as num).toDouble(),
      maxBudget: (json['maxBudget'] as num).toDouble(),
      remainingBudget: (json['remainingBudget'] as num).toDouble(),
    );
  }

  bool get isLowBalance => remainingBudget < 1.0;
  double get percentageUsed => (totalCost / maxBudget) * 100;
}

/// OAuth2 result
class OAuth2Result {
  final bool success;
  final String? accessToken;
  final String? error;
  final String? errorDescription;

  OAuth2Result._({
    required this.success,
    this.accessToken,
    this.error,
    this.errorDescription,
  });

  factory OAuth2Result.success(String accessToken) {
    return OAuth2Result._(success: true, accessToken: accessToken);
  }

  factory OAuth2Result.error(String error, [String? description]) {
    return OAuth2Result._(
      success: false,
      error: error,
      errorDescription: description,
    );
  }
}

/// AI Pass exception
class AiPassException implements Exception {
  final String message;

  AiPassException(this.message);

  @override
  String toString() => 'AiPassException: $message';
}
