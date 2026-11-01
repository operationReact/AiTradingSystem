from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / 'lib/src/presentation/review/mock_trade_intelligence_report.dart'
text = REPORT.read_text()

old = """  Widget _tabBar() {
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
new = """  Widget _tabBar() {
    const tabs = ['Decision', 'Evidence', 'Confidence', 'Outcome', 'Audit'];
    return Container(
      height: 44,
      margin: const EdgeInsets.symmetric(horizontal: 12),
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: const Color(0xFF071529),
        borderRadius: BorderRadius.circular(13),
        border: Border.all(color: const Color(0xFF183050)),
      ),
      child: Row(
        children: tabs
            .map(
              (tab) => Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 1),
                  child: Material(
                    color: _tab == tab ? _C.cyan.withValues(alpha: .12) : Colors.transparent,
                    borderRadius: BorderRadius.circular(9),
                    child: InkWell(
                      onTap: () => setState(() => _tab = tab),
                      borderRadius: BorderRadius.circular(9),
                      child: Center(
                        child: Text(
                          tab,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: _tab == tab ? _C.cyan : _C.muted,
                            fontSize: 9,
                            fontWeight: _tab == tab ? FontWeight.w900 : FontWeight.w700,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            )
            .toList(),
      ),
    );
  }
"""

if new not in text:
    if old not in text:
        raise SystemExit('missing patch anchor: fixed report tabs')
    text = text.replace(old, new, 1)
REPORT.write_text(text)
