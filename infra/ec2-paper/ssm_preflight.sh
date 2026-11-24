#!/usr/bin/env bash
# Read-only diagnostics through approved SSM on the existing ARM64 PAPER EC2.
# Never output credentials, run a broker order or publish network ports.
set -euo pipefail
umask 077

# Report only a bounded stage identifier if any host precondition fails.
stage=identity
trap 'rc=$?; printf "MINTTICK_PREFLIGHT_FAILED_STAGE:%s\\n" "$stage" >&2; exit "$rc"' ERR

test "$(id -u)" -eq 0
stage=architecture
test "$(uname -m)" = "aarch64"
stage=cloud_init
timeout 120 cloud-init status --wait >/dev/null
stage=docker_service
test "$(systemctl is-active docker)" = "active"
stage=ssm_service
test "$(systemctl is-active amazon-ssm-agent)" = "active"
stage=docker_info
docker info --format '{{.ServerVersion}}' >/dev/null
stage=docker_compose
docker compose version >/dev/null
stage=aws_cli
aws --version >/dev/null 2>&1
root="/opt/aitradingsystem-paper"
stage=install_root
test -d "$root" && test ! -L "$root"
stage=runtime_file
test -f "$root/runtime.env" && test ! -L "$root/runtime.env"
stage=runtime_permissions
test "$(stat -c '%a' "$root/runtime.env")" = "600"
stage=postgres_secret_shape
grep -Eq '^POSTGRES_PASSWORD=[0-9a-f]{64}$' "$root/runtime.env"
stage=app_key_shape
grep -Eq '^APP_API_KEY=[0-9a-f]{64}$' "$root/runtime.env"
stage=disk_space
test "$(df -Pk / | awk 'NR==2 {print $4}')" -gt 6291456
stage=intake_diagnostic
python3 - <<'PY'
import json
from pathlib import Path
import stat
import subprocess

root=Path('/opt/aitradingsystem-paper')
runtime={}
for line in (root/'runtime.env').read_text().splitlines():
    name,sep,value=line.partition('=')
    if sep and name.startswith('MINTTICK_FNO_RESEARCH_PRIVATE_INTAKE_'):
        runtime[name]=value
enabled=runtime.get('MINTTICK_FNO_RESEARCH_PRIVATE_INTAKE_ENABLED','false')=='true'
key_present=bool(runtime.get('MINTTICK_FNO_RESEARCH_PRIVATE_INTAKE_REVIEWER_PUBLIC_KEY','').strip())
directory=root/'fno176-attested-intake'
directory_ready=False
try:
    info=directory.stat(follow_symlinks=False)
    directory_ready=(stat.S_ISDIR(info.st_mode) and info.st_uid==10001
                     and stat.S_IMODE(info.st_mode)==0o700)
except OSError:
    pass

ids=subprocess.run(['docker','ps','-q','--filter','label=com.docker.compose.service=backend'],
                   capture_output=True,text=True,check=False).stdout.splitlines()
backend_env={}
readonly_mount=False
if len(ids)==1:
    inspected=subprocess.run(['docker','inspect',ids[0]],capture_output=True,text=True,check=False)
    if inspected.returncode==0:
        try:
            item=json.loads(inspected.stdout)[0]
            backend_env=dict(line.partition('=')[::2] for line in item['Config'].get('Env',[])
                             if '=' in line)
            readonly_mount=any(m.get('Destination')=='/run/minttick-fno176-intake'
                               and m.get('Source')==str(directory) and m.get('RW') is False
                               for m in item.get('Mounts',[]))
        except (ValueError,KeyError,TypeError,IndexError):
            backend_env={}
            readonly_mount=False
wired=(backend_env.get('MINTTICK_FNO_RESEARCH_PRIVATE_INTAKE_ENABLED')
       ==('true' if enabled else 'false')
       and backend_env.get('MINTTICK_FNO_RESEARCH_PRIVATE_INTAKE_REVIEWER_PUBLIC_KEY','')
       ==runtime.get('MINTTICK_FNO_RESEARCH_PRIVATE_INTAKE_REVIEWER_PUBLIC_KEY',''))
print('STR176_INTAKE enabled={} reviewerKeyPresent={} privateDirectory={} backendConfigWired={} readonlyMount={}'
      .format(str(enabled).lower(),str(key_present).lower(),str(directory_ready).lower(),
              str(wired).lower(),str(readonly_mount).lower()))
PY
stage=complete
echo 'ARM64 host SSM preflight PASS: cloud-init, Docker, Compose, AWS CLI, private credentials and disk'
