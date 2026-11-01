import 'package:flutter/material.dart';

import 'src/presentation/review/mock_live_trading.dart';
import 'src/presentation/review/mock_review_app.dart';
import 'src/presentation/sign_in_page.dart';

void main() {
  runApp(const AiTradingUiReviewApp());
}

class AiTradingUiReviewApp extends StatefulWidget {
  const AiTradingUiReviewApp({super.key});

  @override
  State<AiTradingUiReviewApp> createState() => _AiTradingUiReviewAppState();
}

class _AiTradingUiReviewAppState extends State<AiTradingUiReviewApp> {
  bool _authenticated = false;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'AiTradingSystem UI Review',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      darkTheme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF020817),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF13DFF3),
          secondary: Color(0xFF8B4DFF),
          surface: Color(0xFF08162A),
        ),
        snackBarTheme: const SnackBarThemeData(
          behavior: SnackBarBehavior.floating,
        ),
        switchTheme: SwitchThemeData(
          thumbColor: WidgetStateProperty.resolveWith(
            (states) => states.contains(WidgetState.selected)
                ? const Color(0xFF03111F)
                : null,
          ),
          trackColor: WidgetStateProperty.resolveWith(
            (states) => states.contains(WidgetState.selected)
                ? const Color(0xFF13DFF3)
                : null,
          ),
        ),
        inputDecorationTheme: InputDecorationTheme(
          filled: true,
          fillColor: const Color(0xFF071529),
          border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
        ),
        chipTheme: ChipThemeData(
          selectedColor: const Color(0xFF13DFF3),
          backgroundColor: const Color(0xFF0A172A),
          labelStyle: const TextStyle(fontSize: 11),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
          side: const BorderSide(color: Color(0xFF183050)),
        ),
      ),
      home: AnimatedSwitcher(
        duration: const Duration(milliseconds: 260),
        child: _authenticated
            ? const MockLiveReviewHost(
                key: ValueKey('mock-live-review-host'),
                paperApp: MockReviewApp(key: ValueKey('mock-review-app')),
              )
            : SignInPage(
                key: const ValueKey('mock-review-auth'),
                onAuthenticated: () => setState(() => _authenticated = true),
              ),
      ),
    );
  }
}
