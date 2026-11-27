"""Read-only: print matching sentences from an official documentation page (no secrets, no data)."""
import html
import re
import sys

KEYS = (r"static ip|plus|subscription|entitle|terms|restrict|prohibit|redistribut|display|"
        r"personal use|commercial|licen|NFO|NSE_FO|NSE_EQ|segment|exchange|expired|rate limit")


def main(path, name):
    raw = open(path, encoding="utf-8", errors="replace").read()
    text = re.sub(r"<script.*?</script>|<style.*?</style>", " ", raw, flags=re.S | re.I)
    text = html.unescape(re.sub(r"<[^>]+>", " ", text))
    text = re.sub(r"\s+", " ", text)
    print("DOC_PAGE", name, "chars=%d" % len(text))
    shown = 0
    for sentence in re.split(r"(?<=[.!?])\s+", text):
        if shown < 25 and re.search(KEYS, sentence, re.I):
            shown += 1
            print("DOC_SNIPPET", name, sentence[:300])


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
