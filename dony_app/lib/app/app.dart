import 'package:dony/app/router.dart';
import 'package:dony/app/theme.dart';
import 'package:flutter/material.dart';

class DonyApp extends StatelessWidget {
  const DonyApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp.router(
        title: 'dony',
        theme: appTheme,
        routerConfig: appRouter,
        debugShowCheckedModeBanner: false,
      );
}
