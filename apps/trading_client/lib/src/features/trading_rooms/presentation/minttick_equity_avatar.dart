import 'package:flutter/material.dart';

/// Company identity shown on all cash-equity cards.
///
/// Only vetted, fixed company-domain mappings may request a favicon. Other
/// instruments show a deterministic monogram; we do not synthesize a company
/// logo or derive an untrusted image URL from an instrument's display name.
class MintTickEquityAvatar extends StatelessWidget {
  const MintTickEquityAvatar({
    required this.tradingSymbol,
    this.companyName,
    this.size = 42,
    super.key,
  });

  final String tradingSymbol;
  final String? companyName;
  final double size;

  static const Map<String, String> _officialDomains = {
    'RELIANCE': 'ril.com',
    'TCS': 'tcs.com',
    'INFY': 'infosys.com',
    'HDFCBANK': 'hdfcbank.com',
    'ICICIBANK': 'icicibank.com',
    'ITC': 'itcportal.com',
    'SBIN': 'sbi.co.in',
    'BHARTIARTL': 'airtel.in',
    'WIPRO': 'wipro.com',
    'HCLTECH': 'hcltech.com',
    'LT': 'larsentoubro.com',
    'AXISBANK': 'axisbank.com',
    'MARUTI': 'marutisuzuki.com',
    'HINDUNILVR': 'hul.co.in',
  };

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final symbol = tradingSymbol.trim().toUpperCase().replaceFirst(
        RegExp(r'-EQ$'), '');
    final domain = _officialDomains[symbol];
    final label = symbol.isNotEmpty ? symbol : (companyName ?? 'EQ');
    final initials = label.length <= 2 ? label : label.substring(0, 2);
    final fallback = Center(
      child: Text(initials,
        style: TextStyle(fontSize: size * 0.30,
          fontWeight: FontWeight.w800, color: scheme.primary)),
    );
    return Semantics(
      label: '$label company identity',
      child: Container(
        width: size, height: size,
        clipBehavior: Clip.antiAlias,
        decoration: BoxDecoration(
          color: scheme.primary.withValues(alpha: 0.09),
          border: Border.all(color: scheme.outlineVariant.withValues(alpha: 0.7)),
          borderRadius: BorderRadius.circular(size * 0.27),
        ),
        child: domain == null
            ? fallback
            : Padding(
                padding: EdgeInsets.all(size * 0.16),
                child: Image.network(
                  'https://www.google.com/s2/favicons?domain=$domain&sz=128',
                  width: size * 0.68, height: size * 0.68,
                  fit: BoxFit.contain,
                  errorBuilder: (context, error, stack) => fallback,
                ),
              ),
      ),
    );
  }
}
