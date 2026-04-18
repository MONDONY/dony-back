import 'package:firebase_core/firebase_core.dart' show FirebaseOptions;
import 'package:flutter/foundation.dart' show TargetPlatform, defaultTargetPlatform;

class DefaultFirebaseOptions {
  static FirebaseOptions get currentPlatform {
    return switch (defaultTargetPlatform) {
      TargetPlatform.android => android,
      TargetPlatform.iOS => ios,
      _ => throw UnsupportedError(
          'DefaultFirebaseOptions are not supported for this platform.',
        ),
    };
  }

  static const FirebaseOptions android = FirebaseOptions(
    apiKey: 'AIzaSyCqLzqpzH_MKjsplBie4Xdi0xSsTWtNB-k',
    appId: '1:449263493159:android:5f905b06af4f322f086e02',
    messagingSenderId: '449263493159',
    projectId: 'dony-36cb2',
    storageBucket: 'dony-36cb2.firebasestorage.app',
  );

  static const FirebaseOptions ios = FirebaseOptions(
    apiKey: 'AIzaSyDD5_F3BrPcYqo2G5xorNh26L1PePUoYoA',
    appId: '1:449263493159:ios:8be187aef0c0f8b2086e02',
    messagingSenderId: '449263493159',
    projectId: 'dony-36cb2',
    storageBucket: 'dony-36cb2.firebasestorage.app',
    iosBundleId: 'com.dony.dony',
  );
}
