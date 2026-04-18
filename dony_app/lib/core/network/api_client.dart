import 'package:dio/dio.dart';
import 'package:dony/core/error/app_exception.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/foundation.dart';

class ApiClient {
  ApiClient({required String baseUrl}) {
    _dio = Dio(
      BaseOptions(
        baseUrl: baseUrl,
        connectTimeout: const Duration(seconds: 10),
        receiveTimeout: const Duration(seconds: 30),
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
      ),
    );

    _dio.interceptors.add(_AuthInterceptor());

    if (kDebugMode) {
      _dio.interceptors.add(
        LogInterceptor(requestBody: true, responseBody: true),
      );
    }
  }

  late final Dio _dio;

  Dio get dio => _dio;
}

class _AuthInterceptor extends Interceptor {
  @override
  Future<void> onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    // Skip auth for public endpoints (e.g. health check before Firebase is ready)
    if (options.extra['skipAuth'] == true) {
      handler.next(options);
      return;
    }
    try {
      final user = FirebaseAuth.instance.currentUser;
      if (user != null) {
        final token = await user.getIdToken();
        options.headers['Authorization'] = 'Bearer $token';
      }
    } catch (_) {
      // Firebase not yet initialized — proceed without token
    }
    handler.next(options);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    final statusCode = err.response?.statusCode;
    final detail = err.response?.data?['detail'] as String?;

    final AppException appException;
    if (statusCode == 401) {
      appException = UnauthorizedException(detail ?? 'Session expirée');
    } else if (statusCode == 403) {
      appException = ForbiddenException(detail ?? 'Accès refusé');
    } else if (statusCode == 404) {
      appException = NotFoundException(detail ?? 'Ressource introuvable');
    } else if (statusCode == 422) {
      appException = ValidationException(detail ?? 'Données invalides');
    } else if (statusCode != null && statusCode >= 500) {
      appException = ServerException(detail ?? 'Erreur serveur');
    } else {
      appException = NetworkException(
        err.message ?? 'Erreur réseau',
        code: statusCode?.toString(),
      );
    }

    handler.reject(
      DioException(
        requestOptions: err.requestOptions,
        error: appException,
        response: err.response,
        type: err.type,
      ),
    );
  }
}
