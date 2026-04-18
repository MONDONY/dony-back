import 'package:hive_flutter/hive_flutter.dart';

class HiveService {
  static const String offlineQueueBox = 'offline_queue';
  static const String userPrefsBox = 'user_prefs';

  Future<void> init() async {
    await Hive.initFlutter();
    await Hive.openBox<Map>(offlineQueueBox);
    await Hive.openBox(userPrefsBox);
  }

  Box<Map> get offlineQueue => Hive.box<Map>(offlineQueueBox);

  Box get userPrefs => Hive.box(userPrefsBox);
}
