import 'package:dony/app/app.dart';
import 'package:dony/core/di/injection.dart';
import 'package:dony/core/firebase/firebase_options.dart';
import 'package:dony/core/storage/hive_service.dart';
import 'package:dony/features/notifications/data/notification_service.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/material.dart';

const _apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://localhost:8080/api/v1',
);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  await Firebase.initializeApp(options: DefaultFirebaseOptions.currentPlatform);

  await setupDependencies(apiBaseUrl: _apiBaseUrl);
  await getIt<HiveService>().init();
  await getIt<NotificationService>().initialize();

  runApp(const DonyApp());
}
