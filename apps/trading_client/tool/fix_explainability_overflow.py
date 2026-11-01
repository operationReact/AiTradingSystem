from pathlib import Path

path = Path('lib/src/presentation/review/mock_trade_journey.dart')
text = path.read_text()
old = "const Row(children: [Icon(Icons.analytics_outlined, color: _C.cyan), SizedBox(width: 8), Text('Prediction vs outcome', style: TextStyle(fontWeight: FontWeight.w900))]),"
new = "const Row(children: [Icon(Icons.analytics_outlined, color: _C.cyan), SizedBox(width: 8), Expanded(child: Text('Prediction vs outcome', maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontWeight: FontWeight.w900)))]),"
if new not in text:
    if old not in text:
        raise SystemExit('post-trade heading anchor not found')
    text = text.replace(old, new, 1)
path.write_text(text)
