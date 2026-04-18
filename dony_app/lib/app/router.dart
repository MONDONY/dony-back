import 'package:dony/features/splash/presentation/splash_screen.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

final appRouter = GoRouter(
  initialLocation: '/splash',
  routes: [
    GoRoute(
      path: '/splash',
      builder: (context, state) => const SplashScreen(),
    ),
    GoRoute(
      path: '/auth/phone',
      builder: (context, state) => const _PlaceholderScreen(title: 'Phone Auth'),
    ),
    GoRoute(
      path: '/auth/otp',
      builder: (context, state) => const _PlaceholderScreen(title: 'OTP Verification'),
    ),
    GoRoute(
      path: '/home',
      builder: (context, state) => const _PlaceholderScreen(title: 'Home'),
    ),
    GoRoute(
      path: '/announcements',
      builder: (context, state) => const _PlaceholderScreen(title: 'Announcements'),
    ),
    GoRoute(
      path: '/announcements/:id',
      builder: (context, state) {
        final id = state.pathParameters['id']!;
        return _PlaceholderScreen(title: 'Announcement $id');
      },
    ),
    GoRoute(
      path: '/tracking/scan',
      builder: (context, state) => const _PlaceholderScreen(title: 'QR Scanner'),
    ),
    GoRoute(
      path: '/payment/confirm',
      builder: (context, state) => const _PlaceholderScreen(title: 'Payment Confirm'),
    ),
    GoRoute(
      path: '/kyc',
      builder: (context, state) => const _PlaceholderScreen(title: 'KYC'),
    ),
    GoRoute(
      path: '/disputes',
      builder: (context, state) => const _PlaceholderScreen(title: 'Disputes'),
    ),
    GoRoute(
      path: '/admin',
      builder: (context, state) => const _PlaceholderScreen(title: 'Admin'),
    ),
  ],
);

class _PlaceholderScreen extends StatelessWidget {
  const _PlaceholderScreen({required this.title});

  final String title;

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(title)),
        body: Center(child: Text(title)),
      );
}
