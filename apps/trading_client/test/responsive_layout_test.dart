import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/presentation/responsive_layout.dart';

void main() {
  test('canonical responsive classes are stable at boundary widths', () {
    expect(ResponsiveLayout.classify(390), ResponsiveClass.mobile);
    expect(ResponsiveLayout.classify(599), ResponsiveClass.mobile);
    expect(ResponsiveLayout.classify(600), ResponsiveClass.adaptive);
    expect(ResponsiveLayout.classify(1023), ResponsiveClass.adaptive);
    expect(ResponsiveLayout.classify(1024), ResponsiveClass.desktop);
    expect(ResponsiveLayout.classify(1440), ResponsiveClass.desktop);
  });

  test('workspace and metric matrices follow 1/2/4 columns', () {
    for (final width in [390.0, 599.0]) {
      expect(ResponsiveLayout.workspaceColumns(width), 1);
      expect(ResponsiveLayout.metricColumns(width), 1);
    }
    for (final width in [600.0, 900.0, 1023.0]) {
      expect(ResponsiveLayout.workspaceColumns(width), 2);
      expect(ResponsiveLayout.metricColumns(width), 2);
    }
    for (final width in [1024.0, 1440.0]) {
      expect(ResponsiveLayout.workspaceColumns(width), 4);
      expect(ResponsiveLayout.metricColumns(width), 4);
    }
  });
}
