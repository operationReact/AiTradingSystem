from pathlib import Path

path = Path('lib/src/presentation/review/mock_trade_journey.dart')
text = path.read_text()
needle = '  Widget _postTradeLearning() {'

positions = []
start = 0
while True:
    pos = text.find(needle, start)
    if pos < 0:
        break
    positions.append(pos)
    start = pos + len(needle)

if len(positions) <= 1:
    raise SystemExit(0)

# Keep the first method (the responsive version) and remove all later duplicate methods.
for method_start in reversed(positions[1:]):
    brace_start = text.find('{', method_start)
    depth = 0
    method_end = None
    for i in range(brace_start, len(text)):
        if text[i] == '{':
            depth += 1
        elif text[i] == '}':
            depth -= 1
            if depth == 0:
                method_end = i + 1
                break
    if method_end is None:
        raise SystemExit('could not find duplicate method end')
    if method_end < len(text) and text[method_end] == '\n':
        method_end += 1
    text = text[:method_start] + text[method_end:]

path.write_text(text)
