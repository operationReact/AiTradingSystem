#!/usr/bin/env bash
# Invoked through SSM on the dedicated ARM64 account host only.
set -euo pipefail
umask 077

image="${1:?immutable ECR image required}"
domain="${2:?owned account domain required}"
pool="ap-south-1_jz2T8qbiY"
client="6jm18v2mdpcfraip92hrjhqjnd"
region="ap-south-1"
account="098859530205"

[[ "$image" =~ ^098859530205\.dkr\.ecr\.ap-south-1\.amazonaws\.com/minttick-dev-account-api:[0-9a-f]{40}$ ]] || {
  echo 'Rejected nonimmutable/wrong-account image' >&2; exit 2;
}
[[ "$domain" =~ ^[a-z0-9][a-z0-9.-]+[a-z0-9]$ ]] || { echo 'Invalid DNS hostname' >&2; exit 2; }
test "$(id -u)" -eq 0 && test "$(uname -m)" = "aarch64"
root=/opt/minttick-account
test -d "$root" && test -f "$root/runtime.env" && test ! -L "$root/runtime.env"
test "$(stat -c '%a' "$root/runtime.env")" = "600"
source "$root/runtime.env"
test -n "${POSTGRES_PASSWORD:-}"
# Construct an ephemeral environment file, never print it or commit it.
envfile="$(mktemp "$root/.api-env.XXXXXXXX")"
trap 'rm -f -- "$envfile"' EXIT
chmod 0600 "$envfile"
printf 'SPRING_PROFILES_ACTIVE=account-api,prod\n' > "$envfile"
printf 'SPRING_DATASOURCE_URL=jdbc:postgresql://minttick-account-postgres:5432/aitrading\n' >> "$envfile"
printf 'SPRING_DATASOURCE_USERNAME=aitrading\n' >> "$envfile"
printf 'SPRING_DATASOURCE_PASSWORD=%s\n' "$POSTGRES_PASSWORD" >> "$envfile"
printf 'SPRING_DATASOURCE_POOL_SIZE=3\n' >> "$envfile"
printf 'MINTTICK_COGNITO_ENABLED=true\n' >> "$envfile"
printf 'MINTTICK_COGNITO_REGION=%s\n' "$region" >> "$envfile"
printf 'MINTTICK_COGNITO_ISSUER=https://cognito-idp.%s.amazonaws.com/%s\n' "$region" "$pool" >> "$envfile"
printf 'MINTTICK_COGNITO_CLIENT_ID=%s\n' "$client" >> "$envfile"
printf 'APP_CORS_ALLOWED_ORIGINS=https://%s\n' "$domain" >> "$envfile"
printf 'APP_API_KEY=\nAPP_DEV_SESSION_BOOTSTRAP_ENABLED=false\nTRADING_MODE=PAPER\n' >> "$envfile"
printf 'MARKET_DATA_PROVIDER=NONE\n' >> "$envfile"
printf 'NEWS_INGESTION_ENABLED=false\nOFFICIAL_SOURCE_SCHEDULER_ENABLED=false\n' >> "$envfile"
printf 'AI_RESEARCH_WORKER_CLIENT_ENABLED=false\n' >> "$envfile"
printf 'AI_RESEARCH_WORKER_READINESS_ENABLED=false\n' >> "$envfile"

aws ecr get-login-password --region "$region" |
  docker login --username AWS --password-stdin "$account.dkr.ecr.$region.amazonaws.com" >/dev/null
docker pull "$image" >/dev/null
docker network inspect minttick-account-net >/dev/null 2>&1 || docker network create minttick-account-net >/dev/null
docker volume inspect minttick-account-db >/dev/null 2>&1 || docker volume create minttick-account-db >/dev/null
docker volume inspect minttick-account-caddy-data >/dev/null 2>&1 || docker volume create minttick-account-caddy-data >/dev/null
docker volume inspect minttick-account-caddy-config >/dev/null 2>&1 || docker volume create minttick-account-caddy-config >/dev/null

if ! docker inspect minttick-account-postgres >/dev/null 2>&1; then
  docker run -d --name minttick-account-postgres --restart unless-stopped \
    --network minttick-account-net --memory 384m --pids-limit 128 \
    -e POSTGRES_DB=aitrading -e POSTGRES_USER=aitrading \
    --env-file "$root/runtime.env" \
    -v minttick-account-db:/var/lib/postgresql/data \
    postgres:16-alpine >/dev/null
else
  docker start minttick-account-postgres >/dev/null || true
fi
for attempt in $(seq 1 60); do
  docker exec minttick-account-postgres pg_isready -U aitrading -d aitrading >/dev/null 2>&1 && break
  sleep 2
done
docker exec minttick-account-postgres pg_isready -U aitrading -d aitrading >/dev/null

# Recreate only the dedicated account backend. It has no published host port.
docker rm -f minttick-account-backend >/dev/null 2>&1 || true
docker run -d --name minttick-account-backend --restart unless-stopped \
  --network minttick-account-net --memory 1200m --pids-limit 256 \
  --security-opt no-new-privileges:true \
  --env-file "$envfile" \
  -e JAVA_TOOL_OPTIONS='-Xms128m -Xmx768m -XX:ActiveProcessorCount=2' \
  "$image" >/dev/null

# Caddy is the ONLY service with host-mapped ports. ACME uses 80/443.
printf '%s {\n  encode zstd gzip\n  reverse_proxy minttick-account-backend:8080\n}\n' "$domain" > "$root/Caddyfile"
chmod 0600 "$root/Caddyfile"
docker rm -f minttick-account-proxy >/dev/null 2>&1 || true
docker run -d --name minttick-account-proxy --restart unless-stopped \
  --network minttick-account-net --memory 128m --pids-limit 128 \
  -p 80:80 -p 443:443 \
  -v "$root/Caddyfile:/etc/caddy/Caddyfile:ro" \
  -v minttick-account-caddy-data:/data \
  -v minttick-account-caddy-config:/config \
  caddy:2.10-alpine >/dev/null
for attempt in $(seq 1 40); do
  if curl --fail --silent --show-error --max-time 8 \
       --output /dev/null "https://$domain/actuator/health/readiness" 2>/dev/null; then
    code="$(curl --silent --output /dev/null --write-out '%{http_code}' \
      --max-time 8 "https://$domain/api/auth/cognito/me")"
    test "$code" = 401 || test "$code" = 403
    echo 'MintTick account HTTPS ready and protected; no trade routes published'
    exit 0
  fi
  sleep 5
done
echo 'Account release incomplete: valid HTTPS and Spring readiness not established' >&2
exit 1
