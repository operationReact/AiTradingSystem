# V4-09 / V81 — Backend-only private HTTPS research worker endpoint

## The exact gap this closes

V76 and V77 created a default-disabled private-subnet Python worker scaffold and optional ECR/Secrets/Logs/S3 connectivity. They left a direct Java-to-worker HTTP/8090 security-group rule. Java V72 rejects non-loopback plain HTTP, so an ECS worker could not be legitimately reached using that path.

V81 removes the direct Java-to-Python ingress rule. It instead adds a **separate opt-in** internal AWS Application Load Balancer (ALB) that accepts backend-only HTTPS/443, validates a publicly trusted ACM TLS certificate against a private DNS hostname, and privately proxies requests on HTTP/8090 to the dormant worker task.

**TLS stops at the ALB:** the final ALB-to-worker hop is HTTP inside the VPC, not mutually authenticated or end-to-end TLS. This is an explicit trust boundary, not a claim of mTLS. The worker independently requires a private token and strict hashed model-artifact startup validation.

## AWS resources, all default OFF

- `research_worker_private_tls_enabled=false` creates **zero** V81 resources by default. Terraform requires `research_worker_scaffold_enabled=true` and `research_worker_private_endpoints_enabled=true` before this third opt-in can be enabled. All can incur costs when enabled (especially two-AZ ALB, interface endpoints, private hosted zone and logs). No automatic apply or cost approval is supplied by this PR.
- `aws_lb.research_worker_private_tls`: **internal** ALB in the two private subnets, with no public IP and only HTTPS/443 listener. Its default response is **403**. Only the exact `/v1/research/artifact-readiness` and `/v1/research/artifact-inference` paths are forwarded; no public `/health`, `/metrics`, docs, admin, redirect or arbitrary proxy route.
- A new dedicated Java-backend identity security group is attached **only** to `aws_ecs_service.backend` (the legacy shared ECS SG is also used by the frontend). The internal ALB only accepts inbound HTTPS 443 **from that new backend-only identity SG**. The ALB only sends HTTP/8090 to worker tasks; the worker security group accepts **only** this ALB as its HTTP/8090 ingress source. Direct backend/frontend-to-worker plaintext is removed. Existing general backend egress policy is not widened.
- `aws_lb_target_group.research_worker_private_tls` is ip/HTTP1 on port 8090; the ALB health check uses the container's `/health`. A 200 health check is necessary, **not proof** of registered model custody or Java V72 original-enrollment match.
- V76 ECS research worker service remains **`desired_count=0`** and `enable_execute_command=false`; a conditional target-group registration appears only with V81 opt-in. If workers are absent, no service is running and the ALB cannot complete a legitimate readiness request.
- Route53 **private** hosted zone `internal.<configured domain_name>` is associated with the existing VPC. Its A alias `research.internal.<configured domain_name>` resolves only from the associated VPC. The alias does not evaluate target health; DNS resolution is **not** model readiness.
- A separate ACM public-certificate request names that exact FQDN. Its DNS **validation CNAME** is placed in the existing **public** `domain_name` hosted zone. There is no public A record for the worker. ACM issuance requires the public parent zone actually to be delegated at the domain registrar; Terraform will not magically validate an undelegated domain. The ordinary Java 21 HttpClient verifies the certificate name and CA; no global TLS trust-all configuration or cert-verification bypass is added.

## Operator prerequisites before any future AWS apply

1. Verify the current AWS identity is **AiTradingSystem account `816344830716`**, `us-east-1` (not the Bro & Bro AWS account). Confirm the live Terraform state and main VPC/private subnet route tables match this repository. The optional ALB includes an account/region precondition.
2. Confirm the parent `domain_name` is actually controlled and its authoritative DNS nameservers are delegated to the **same** public Route53 hosted zone managed by Terraform. ACM DNS validation uses that public zone, while the research A record stays private. Inspect actual DNS and certificate SAN on a real environment.
3. Review monthly ALB/interface endpoint/private DNS costs and AWS quotas. If approved, turn on the three explicit Terraform vars only with the audited full SHA worker ECR image tag and existing Secrets Manager worker-token **ARN**, not token bytes.
4. Inspect a **plan** before any apply. A separate backend SG attachment may cause a backend service deployment/new task registration. That can affect current PAPER traffic; it must be an explicit reviewed operations window. A merged PR changes **code only** and cannot approve/redeploy services.
5. Verify Java service startup remains `TRADING_MODE=PAPER`, `AI_RESEARCH_WORKER_CLIENT_ENABLED=false` and `AI_RESEARCH_WORKER_READINESS_ENABLED=false`. V81 never sets these opt-in flags to true or injects the research token into Java automatically. At a later independently reviewed operator gate, set `AI_RESEARCH_WORKER_URL=https://research.internal.<domain_name>` and supply the same private token to Java via a **separate least-privilege ECS execution-role Secrets Manager reference**, not plaintext.
6. Before any Java readiness probe: perform V73 backend and V75 worker **read-only** IAM runtime inspections using operator-provisioned GitHub OIDC roles, verify worker image digest, proper strict startup/model bundle, ALB TLS identity and private DNS resolution from the Java service, then use genuine ADMIN original-session V72 roster reconciliation in PAPER mode. Endpoint accessibility alone does **not** authorize research dispatch or model inference.

## Proof boundaries

- V81 GitHub CI tests only the Terraform graph and source constraints. `terraform fmt -check`, `terraform validate`, and application tests cannot attest actual live AWS certificates, VPC routing, model files, commercial source rights, broker execution or real-market results.
- All model files must be independently licensed, version/fingerprint matched, and staged through V80. No such actual artifact package was supplied to this PR. Even after TLS works, market license + point-in-time custody, real net-of-fee/slippage OOS performance, sustained PAPER/SHADOW soak and broker reconciliation are separate gates.
- LIVE trading and capital authority remain forbidden; no orders, worker startup or AWS mutations happen in this PR.

References:
- AWS [internal ALB network mapping](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/create-application-load-balancer.html).
- AWS [ALB TLS server certificates](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/https-listener-certificates.html).
- AWS [ALB target protocol behavior](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-target-groups.html).
