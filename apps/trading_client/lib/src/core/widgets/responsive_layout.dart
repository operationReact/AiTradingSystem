enum ResponsiveClass { mobile, adaptive, desktop }

abstract final class ResponsiveLayout {
  static const double mobileBreakpoint = 600;
  static const double desktopBreakpoint = 1024;

  static ResponsiveClass classify(double width) {
    if (width < mobileBreakpoint) return ResponsiveClass.mobile;
    if (width < desktopBreakpoint) return ResponsiveClass.adaptive;
    return ResponsiveClass.desktop;
  }

  static bool isCompactPhone(double width, double height) => width < mobileBreakpoint && height <= 920;

  static int workspaceColumns(double width) => switch (classify(width)) {
        ResponsiveClass.mobile => width >= 360 ? 2 : 1,
        ResponsiveClass.adaptive => 2,
        ResponsiveClass.desktop => 4,
      };

  static int metricColumns(double width) => switch (classify(width)) {
        ResponsiveClass.mobile => width >= 360 ? 2 : 1,
        ResponsiveClass.adaptive => 2,
        ResponsiveClass.desktop => 4,
      };

  static double horizontalPadding(double width) => switch (classify(width)) {
        ResponsiveClass.mobile => width <= 320 ? 10 : 12,
        ResponsiveClass.adaptive => 24,
        ResponsiveClass.desktop => 56,
      };

  static double topPadding(double width) => classify(width) == ResponsiveClass.mobile ? 10 : 20;

  static double sectionGap(double width) => classify(width) == ResponsiveClass.mobile ? 12 : 18;

  static double cardPadding(double width) => classify(width) == ResponsiveClass.mobile ? 14 : 20;

  static double heroPadding(double width) => switch (classify(width)) {
        ResponsiveClass.mobile => 14,
        ResponsiveClass.adaptive => 26,
        ResponsiveClass.desktop => 36,
      };

  static double heroRadius(double width) => classify(width) == ResponsiveClass.mobile ? 18 : 24;

  static double contentMaxWidth(double width) => switch (classify(width)) {
        ResponsiveClass.mobile => double.infinity,
        ResponsiveClass.adaptive => 760,
        ResponsiveClass.desktop => 1180,
      };
}
