import 'package:dio/dio.dart' show Options;
import 'package:dony/core/di/injection.dart';
import 'package:dony/core/network/api_client.dart';
import 'package:flutter/material.dart';

class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key});

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen> {
  _Status _status = _Status.checking;
  String _detail = '';

  @override
  void initState() {
    super.initState();
    _checkBackend();
  }

  Future<void> _checkBackend() async {
    setState(() {
      _status = _Status.checking;
      _detail = '';
    });
    try {
      final response = await getIt<ApiClient>().dio.get<Map<String, dynamic>>(
            '/actuator/health',
            options: Options(extra: {'skipAuth': true}),
          );
      final backendStatus = response.data?['status'] as String? ?? 'UNKNOWN';
      if (mounted) {
        setState(() {
          _status = backendStatus == 'UP' ? _Status.ok : _Status.error;
          _detail = 'Backend: $backendStatus';
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _status = _Status.error;
          _detail = e.toString().replaceAll(RegExp(r'DioException.*\['), '[');
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF1A6B3C),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text(
              'dony',
              style: TextStyle(
                color: Colors.white,
                fontSize: 56,
                fontWeight: FontWeight.bold,
                letterSpacing: 6,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'P2P · Afrique',
              style: TextStyle(color: Colors.white54, fontSize: 14, letterSpacing: 2),
            ),
            const SizedBox(height: 56),
            _buildStatusWidget(),
            if (_detail.isNotEmpty) ...[
              const SizedBox(height: 12),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 32),
                child: Text(
                  _detail,
                  style: const TextStyle(color: Colors.white54, fontSize: 11),
                  textAlign: TextAlign.center,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
            if (_status == _Status.error) ...[
              const SizedBox(height: 20),
              OutlinedButton.icon(
                onPressed: _checkBackend,
                icon: const Icon(Icons.refresh, color: Colors.white),
                label: const Text('Réessayer', style: TextStyle(color: Colors.white)),
                style: OutlinedButton.styleFrom(side: const BorderSide(color: Colors.white38)),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildStatusWidget() => switch (_status) {
        _Status.checking => const Column(
            children: [
              CircularProgressIndicator(color: Colors.white, strokeWidth: 2),
              SizedBox(height: 12),
              Text('Connexion au serveur...', style: TextStyle(color: Colors.white70)),
            ],
          ),
        _Status.ok => const Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.check_circle_outline, color: Color(0xFF69F0AE), size: 26),
              SizedBox(width: 8),
              Text(
                'Serveur connecté ✓',
                style: TextStyle(color: Color(0xFF69F0AE), fontSize: 15, fontWeight: FontWeight.w600),
              ),
            ],
          ),
        _Status.error => const Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.wifi_off, color: Color(0xFFFF5252), size: 26),
              SizedBox(width: 8),
              Text(
                'Serveur inaccessible',
                style: TextStyle(color: Color(0xFFFF5252), fontSize: 15, fontWeight: FontWeight.w600),
              ),
            ],
          ),
      };
}

enum _Status { checking, ok, error }
