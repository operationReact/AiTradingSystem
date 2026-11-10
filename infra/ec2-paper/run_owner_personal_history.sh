#!/usr/bin/env bash
# Exact SHA-reviewed owner research entrypoint deployed with the PAPER release.
# Never accepts arbitrary arguments, paths, tokens, secrets or orders.
set -Eeuo pipefail
umask 077
test "$(id -u)" -eq 0
test "$(uname -m)" = "aarch64"
root=/opt/aitradingsystem-paper
test -d "$root" && test ! -L "$root"
for name in probe_owner_angelone_auth.py owner_personal_angelone_history.py; do
  test -f "$root/$name" && test ! -L "$root/$name"
  test "$(stat -c '%u:%a' "$root/$name")" = "0:600"
done
python3 - "$root" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1])
auth=(p/'probe_owner_angelone_auth.py').read_text()
collector=(p/'owner_personal_angelone_history.py').read_text()
assert '\nif __name__ == "__main__":' in auth
source=auth.split('\nif __name__ == "__main__":',1)[0]+'\n'+collector
assert len(source)<42000 and source.count('if __name__ == "__main__":')==1
exec(compile(source,'<signed-private-personal-history>','exec'),
     {'__name__':'__main__'})
PY
