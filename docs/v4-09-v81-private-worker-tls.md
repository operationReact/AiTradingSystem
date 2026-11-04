# V4-09/V81 — Opt-in private HTTPS between Java and Python research worker

V80 introduced an **operator-only, verified local research model bundle** but did not create a deployable, trusted network path to the dormant ECS Python worker. V76/V77 previously allowed **direct private HTTP from Java backend security group to the worker on port 8090**, which was not a TLS-authenticated origin. V81 removes that direct ingress, adds an opt-in TLS edge and attaches the dormant worker ECS service to that edge's target group.

## Network architecture and precise TLS boundary

```text
Java backend ECS task (current public subnet, existing backend SG)
    HTTPS 443, SNI and JVM certificate validation to research-worker.<domain>
    private Route 53 A alias (private hosted zone, associated VPC only)
        -> INTERNAL Application Load Balancer (private subnets, ACM TLS cert)
           TLS termination + standard Java HTTP client trust verification
           Internal ALB SG inbound HTTPS 443 ONLY from Java backend SG
           Internal ALB SG outbound port 8090 ONLY to Python worker SG
               -> HTTP 8090 over VPC-local subnet path
                  Python worker SG inbound ONLY from internal ALB SG
                  Python container private subnet, no public IP
                  private X-Research-Worker-Token still required on
                  /v1/research/artifact-readiness and artifact-inference
```

**TLS is terminated at the internal ALB and is NOT end-to-end TLS between the ALB and Python.** The ALB-to-worker hop remains private HTTP, isolated by both security groups, while the sensitive research token is still transmitted on that private hop. A future strict end-to-end encryption/mTLS policy must use a separately designed secure target protocol, certificate trust and key rotation; do not describe this ALB configuration as end-to-end encryption.

The ALB and Python target have **no public listener, internet-facing IP, direct Java-to-Python HTTP ingress, public DNS A record or NAT route**. This limits reachability but does not by itself prove deployed security, model governance, or an authorized Java research token.

## Disabled-by-default, chargeable operator opt-in

- `research_worker_tls_enabled=false` by default. V81 adds **ZERO resources**, and does not alter live Java backend environment variables in a default Terraform apply.
- Before changing it to true, the operator must separately enable the V76 dormant scaffold, the V77 private ECR/Logs/Secrets Manager/S3 endpoints and existing `enable_https=true`. Terraform cross-variable validation checks those prerequisites.
- Enabling V81 provisions an internal multi-AZ ALB, IP target group, ACM-issued certificate, public DNS *validation CNAME*, a **private hosted zone** named `research-worker.<domain_name>` with a private VPC-only A alias, and restricted TLS/target security group rules. ACM DNS validation requires the **public** parent `aws_route53_zone.main` to be delegated at the registrar. There is no public worker A/AAAA record.
- The public certificate is issued in the **same AWS region** as the ALB. The V81 Terraform lifecycle precondition permits only account `816344830716`, region `us-east-1`. Use an existing, actually delegated `domain_name` (the application currently references `maargglobal.com` in its deployment workflow). Do not infer DNS authority or assume certificate validation has succeeded from a plan.
- Private ALB, private hosted zone, endpoints and related data transfer incur charges when explicitly enabled. Review current AWS pricing and an exact account/region Terraform plan **before** any apply. This PR never enables these switches, runs Terraform apply, or creates AWS resources.
- V81 deliberately leaves the worker ECS service `desired_count=0` and `enable_execute_command=false`. An empty target group is **expected** until a separate deployment is authorized. A healthy ALB listener is **not** proof of a running or model-ready worker.
- V81 does **not** inject a worker URL, research token, readiness flag or model inference flag into the backend's ECS task definition. Java client opt-in remains disabled. Do not enable `AI_RESEARCH_WORKER_CLIENT_ENABLED` or `AI_RESEARCH_WORKER_READINESS_ENABLED` based on the existence of DNS alone.

## Offline acceptance and runtime follow-up

- `terraform fmt -check`, `terraform validate`, V76/V77/V81 standalone Python source contracts, Java/PostgreSQL tests, Docker and Python research contracts must pass on the **exact PR commit**.
- Independent AWS validation must confirm: actual public certificate issuance, private hosted zone association, Route 53 private resolution from Java backend ECS task, hostname/SNI chain validation against the worker name, TLS ALB listener, SG-bound backend-to-ALB-to-worker traffic, and negative tests proving public/HTTP worker URLs and direct port 8090 traffic cannot reach Python.
- Separate approved Java configuration must supply the exact `https://research-worker.<domain_name>` URL and independently provisioned private token to Java through Secrets Manager. The token must agree with the Python execution-role secret reference and be explicitly permission-limited; neither exists in V81 automatically.
- A reviewed, source-rights-cleared V80 model bundle and immutable worker image, AWS ECR publish, task rollout/health, V75 ECR digest attestation, actual V72 ADMIN original-enrollment roster reconciliation, PAPER/SHADOW evaluation, broker reconciliation and explicit LIVE/capital release gates **remain independently blocked**.

All inspection tooling must remain read-only and sanitized. Failure of a deployment, model, custody, cost or identity check is `BLOCKED`; never infer live market authorization from TLS or synthetic CI.

### Architecture references

- [AWS internal vs internet-facing load balancers](https://docs.aws.amazon.com/elasticloadbalancing/latest/userguide/how-elastic-load-balancing-works.html)
- [AWS infrastructure security: SG-bound TLS listener and restricted targets](https://docs.aws.amazon.com/elasticloadbalancing/latest/userguide/infrastructure-security.html)
- [AWS service-to-service private Route 53 alias names](https://aws.github.io/aws-networking-best-practices/application-networking/service-to-service/)
- [AWS ACM certificates on HTTPS ALBs](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/create-application-load-balancer.html)
