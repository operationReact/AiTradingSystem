import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/core/widgets/responsive_layout.dart';

void main() {
  test('canonical responsive classes cover every mandatory viewport width', () {
    for (final width in [320.0, 375.0, 411.0]) {
      expect(ResponsiveLayout.classify(width), ResponsiveClass.mobile);
    }
    expect(ResponsiveLayout.classify(599), ResponsiveClass.mobile);
    expect(ResponsiveLayout.classify(600), ResponsiveClass.adaptive);
    expect(ResponsiveLayout.classify(768), ResponsiveClass.adaptive);
    expect(ResponsiveLayout.classify(1023), ResponsiveClass.adaptive);
    expect(ResponsiveLayout.classify(1024), ResponsiveClass.desktop);
    expect(ResponsiveLayout.classify(1440), ResponsiveClass.desktop);
  });

  test('workspace and metric matrices follow 1/2/4 columns at mandatory widths', () {
    for (final width in [320.0, 375.0, 411.0, 599.0]) {
      expect(ResponsiveLayout.workspaceColumns(width), 1);
      expect(ResponsiveLayout.metricColumns(width), 1);
    }
    for (final width in [600.0, 768.0, 1023.0]) {
      expect(ResponsiveLayout.workspaceColumns(width), 2);
      expect(ResponsiveLayout.metricColumns(width), 2);
    }
    for (final width in [1024.0, 1440.0]) {
      expect(ResponsiveLayout.workspaceColumns(width), 4);
      expect(ResponsiveLayout.metricColumns(width), 4);
    }
  });
}
