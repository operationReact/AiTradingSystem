# Next milestone: EC2 PAPER backend + PostgreSQL + Flutter Web

## Confirmed infrastructure (already deployed)

- Account `098859530205`, region `ap-south-1`.
- EC2 `i-09c01b470fb39b85a`, `t4g.medium` ARM64, encrypted persistent 35 GiB gp3 root EBS.
- Zero ingress/security-group ports, SSM-core instance role, local secrets initialized by cloud-init at `/opt/aitradingsystem-paper/runtime.env`.
- GitHub `main` workflow **PAPER EC2 - manual Start / Stop / Check status** installed and successfully read-only tested.
- Independent EventBridge nightly stop **22:00 Asia/Kolkata** enabled. EC2 is currently stopped.
- No public backend, web, RDS or PostgreSQL service deployed.

## Prepared here (offline only; NOT yet deployed to AWS)

- `paper-compose.yml`: PostgreSQL 16 with persistent Docker volume on EBS; Java Spring Boot (ARM64) with Flyway startup; Flutter Web Nginx proxy. Web/API bind **only 127.0.0.1**, database has no host port. `TRADING_MODE=PAPER` and broker/research/ingestion jobs disabled.
- `run_paper.sh`: on-instance idempotent release script requiring exactly two pre-transferred, immutable Git SHA ARM64 images, checks nonroot Java image, reuses host-local secrets, attempts `pg_dump` backup before upgrades, runs Docker Compose, verifies real Java and Flutter-proxied API health. No EC2 lifecycle operations and no external Docker image publishing.
- Offline tests for dangerous port exposure, database volume, PAPER mode, local secrets, image tagging and code syntax.

## Remaining work (requires separately reviewed AWS access)

1. **Bootstrap readiness audit on the existing EC2**: Run GitHub manual **Start** when a test session is desired. Verify SSM agent is Online, cloud-init finished, Docker+Compose installed, persistent runtime secret file exists, and no inbound listener is exposed. Before this, do not assume bootstrapping succeeded merely because EC2 is started.
2. **Least-privileged GitHub deployment OIDC role**, trusted only on `dev`: SSM SendCommand/GetCommandInvocation to the exact existing tagged PAPER instance, plus scoped immutable artifact staging. The existing EC2 SSM instance profile will need scoped read access to those artifacts. No broad SSH, no public ingress, no access to Terraform state, no broker keys.
3. **Immutable release artifacts built on GitHub-hosted runners**: Build exact SHA ARM64 Java and Flutter containers (the existing `ec2-paper-arm64-images.yml` gate already validates these offline). Stage their Docker image archive and compose/run scripts in a separately scoped encrypted, short-retention object storage location. Verify cryptographic SHA256 before Docker load on EC2. Do not build the images on the 4 GiB VM.
4. **SSM deployment and connected smoke**: Send exactly allowlisted release commands via SSM, verify account/instance/stopped-vs-running state, load images, run `run_paper.sh`, confirm DB Flyway migrations and local API and reverse-proxy health. Detect failures before marking a deployment successful. Keep SSM command output secret-free.
5. **Operational acceptance**: Test repeated GitHub Start/Stop clicks, Docker restart policies after machine restart, PostgreSQL persistence after EC2 stop/start, 22:00 IST shutdown behavior, and version pinning. Add independent off-instance encrypted database backup retention **before storing important user data**.
6. **Controlled remote frontend access**: Select a separate secured HTTPS/SSM tunnel design before exposing Flutter Web to users. Do not open HTTP, 5432 or SSH merely to make testing easier.

**Avoid reusing stale workflows** targeting old `us-east-1` ECS or the retired Lightsail deployment path. Those workflows are not authorized for the new Mumbai EC2 architecture.

## Cost/safety constraints

Keep the same EC2/EBS (no new RDS/ECS/ALB/NAT). The server remains stopped except during explicitly triggered work. EBS storage is billable even while stopped. Application containers resume with Docker when EC2 starts, so keep the nightly stop schedule and enforce PAPER-only trading mode.
