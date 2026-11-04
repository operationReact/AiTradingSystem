# V4-09/V77 — Private AWS endpoints for dormant PAPER research worker

The existing V76 research worker is a **disabled-by-default Terraform scaffold** with `desired_count=0`. Private Fargate tasks require image-pull, secret and logging paths with no public NAT/IP. V77 adds optional private endpoints to make those paths possible **without** starting tasks or changing LIVE/risk controls.

## Cost and deployment gates

- `research_worker_private_endpoints_enabled=false` by default; **zero additional resources** are created during an ordinary apply.
- To opt in after an explicit cost/plan review, `research_worker_scaffold_enabled=true` must also be set; otherwise Terraform rejects the variable.
- When enabled, four interface endpoint services (`ecr.api`, `ecr.dkr`, `logs`, `secretsmanager`) each span two private subnets and therefore incur ongoing endpoint and data charges. One Amazon S3 Gateway endpoint is added to the existing VPC **main route table**, assumed to serve the private subnets because existing Terraform associates only public subnets to a separate public route table. Verify actual AWS route-table associations before applying: imported/out-of-band associations can differ.
- Worker SG no longer allows blanket `0.0.0.0/0` egress. Its only conditional outbound HTTPS rules target the worker VPC endpoint SG and the region's S3 managed prefix list. Endpoint SG permits inbound 443 from only the worker security group. No NAT gateway, public IP, public ALB or new worker execution privilege.
- IAM existing V76 execution role can pull the private ECR image, access its exact secret reference and send CloudWatch logs. Actual task image/manifest bytes still need a separate, reviewed release.
- **Terraform code merge is not an AWS apply.** Nothing here creates a running research worker. `desired_count=0` remains immutable by Terraform's normal desired-state reconciliation.

## Critical unresolved operational dependencies

1. The worker image must contain a real, independently reviewed local manifest and pinned model files (V76's declared `/opt/research/manifest.json` path is not itself a mounted file).
2. Java V72 permits HTTPS or loopback HTTP only. Private VPC HTTP directly to Uvicorn on port 8090 **is not an allowed Java production worker URL**. A separate TLS/private identity and stable DNS design is required before enabling authenticated Java readiness or dispatch.
3. The operator must independently provision and verify OIDC inspection roles, registered token secret, private networking, ECS release and ECR digest. Default read-only V73/V75 manual workflows remain unexecuted.
4. Data rights for Indian sources, genuine point-in-time model evidence, representative PAPER/SHADOW soak, broker reconciliation and LIVE/capital release authority remain **unverified and disabled**.

## Reference

- [AWS Fargate VPC endpoint considerations](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/vpc-endpoints.html).
- [Amazon ECR interface endpoints and S3 gateway for Fargate 1.4+](https://docs.aws.amazon.com/AmazonECR/latest/userguide/vpc-endpoints.html).
- [ECS task execution role](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_execution_IAM_role.html).

Acceptance is a green exact-head Python policy contract, Terraform fmt/validate and existing Java/PostgreSQL/Docker/Flutter CI; this **never** verifies AWS runtime itself.
