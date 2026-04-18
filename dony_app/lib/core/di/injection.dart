import 'package:dony/core/network/api_client.dart';
import 'package:dony/core/storage/hive_service.dart';
import 'package:dony/features/notifications/data/notification_service.dart';
import 'package:get_it/get_it.dart';

final getIt = GetIt.instance;

Future<void> setupDependencies({required String apiBaseUrl}) async {
  getIt.registerLazySingleton<HiveService>(() => HiveService());
  getIt.registerLazySingleton<ApiClient>(() => ApiClient(baseUrl: apiBaseUrl));
  getIt.registerLazySingleton<NotificationService>(() => NotificationService());
}
