enum ResponsiveClass { mobile, adaptive, desktop }

abstract final class ResponsiveLayout {
  static const double mobileBreakpoint = 600;
  static const double desktopBreakpoint = 1024;

  static ResponsiveClass classify(double width) {
    if (width < mobileBreakpoint) return ResponsiveClass.mobile;
    if (width < desktopBreakpoint) return ResponsiveClass.adaptive;
    return ResponsiveClass.desktop;
  }

  static int workspaceColumns(double width) => switch (classify(width)) {
        ResponsiveClass.mobile => 1,
        ResponsiveClass.adaptive => 2,
        ResponsiveClass.desktop => 4,
      };

  static int metricColumns(double width) => switch (classify(width)) {
        ResponsiveClass.mobile => 1,
        ResponsiveClass.adaptive => 2,
        ResponsiveClass.desktop => 4,
      };

  static double horizontalPadding(double width) => switch (classify(width)) {
        ResponsiveClass.mobile => 16,
        ResponsiveClass.adaptive => 24,
        ResponsiveClass.desktop => 56,
      };

  static double heroPadding(double width) => switch (classify(width)) {
        ResponsiveClass.mobile => 24,
        ResponsiveClass.adaptive => 32,
        ResponsiveClass.desktop => 40,
      };

  static double contentMaxWidth(double width) => switch (classify(width)) {
        ResponsiveClass.mobile => double.infinity,
        ResponsiveClass.adaptive => 760,
        ResponsiveClass.desktop => 1180,
      };
}
