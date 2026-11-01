from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / 'lib/src/presentation/review/mock_trade_intelligence_report.dart'
text = REPORT.read_text()

old_tabs = """  Widget _tabBar() {
    return SizedBox(
      height: 44,
      child: ListView(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        scrollDirection: Axis.horizontal,
        children: ['Decision', 'Evidence', 'Confidence', 'Outcome', 'Audit']
            .map(
              (tab) => Padding(
                padding: const EdgeInsets.only(right: 8),
                child: ChoiceChip(
                  label: Text(tab),
                  selected: _tab == tab,
                  onSelected: (_) => setState(() => _tab = tab),
                ),
              ),
            )
            .toList(),
      ),
    );
  }
"""
new_tabs = """  Widget _tabBar() {
    return SizedBox(
      height: 44,
      child: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        scrollDirection: Axis.horizontal,
        child: Row(
          children: ['Decision', 'Evidence', 'Confidence', 'Outcome', 'Audit']
              .map(
                (tab) => Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: ChoiceChip(
                    label: Text(tab),
                    selected: _tab == tab,
                    onSelected: (_) => setState(() => _tab = tab),
                  ),
                ),
              )
              .toList(),
        ),
      ),
    );
  }
"""
if old_tabs in text:
    text = text.replace(old_tabs, new_tabs, 1)
elif new_tabs not in text:
    raise SystemExit('missing patch anchor: trade intelligence tab bar')

old_risk = "const Row(children: [Icon(Icons.verified_user_outlined, color: _C.green, size: 18), SizedBox(width: 8), Text('Mandatory risk boundary', style: TextStyle(fontWeight: FontWeight.w900))]),"
new_risk = "const Row(children: [Icon(Icons.verified_user_outlined, color: _C.green, size: 18), SizedBox(width: 8), Expanded(child: Text('Mandatory risk boundary', maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontWeight: FontWeight.w900)))]),"
if old_risk in text:
    text = text.replace(old_risk, new_risk, 1)
elif new_risk not in text:
    raise SystemExit('missing patch anchor: risk boundary row')

REPORT.write_text(text)
