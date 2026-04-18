import 'package:equatable/equatable.dart';

abstract class AppException extends Equatable implements Exception {
  const AppException(this.message, {this.code});

  final String message;
  final String? code;

  @override
  List<Object?> get props => [message, code];

  @override
  String toString() => 'AppException($code): $message';
}

class NetworkException extends AppException {
  const NetworkException(super.message, {super.code});
}

class UnauthorizedException extends AppException {
  const UnauthorizedException([super.message = 'Unauthorized'])
      : super(code: 'UNAUTHORIZED');
}

class ForbiddenException extends AppException {
  const ForbiddenException([super.message = 'Forbidden'])
      : super(code: 'FORBIDDEN');
}

class NotFoundException extends AppException {
  const NotFoundException([super.message = 'Resource not found'])
      : super(code: 'NOT_FOUND');
}

class ValidationException extends AppException {
  const ValidationException(super.message, {super.code, this.errors});

  final Map<String, List<String>>? errors;

  @override
  List<Object?> get props => [message, code, errors];
}

class ServerException extends AppException {
  const ServerException([super.message = 'Server error'])
      : super(code: 'SERVER_ERROR');
}

class StorageException extends AppException {
  const StorageException(super.message, {super.code});
}
