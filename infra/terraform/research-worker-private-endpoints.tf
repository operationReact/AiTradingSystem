# V4-09 / V77 — optional private egress for dormant PAPER Python worker.
# This module creates NO resources unless both the dormant worker scaffold and
# this independent cost-bearing opt-in are explicitly enabled.
#
# Fargate 1.4+ requires ECR API + DKR plus S3 for private ECR image layers.
# Secrets Manager and awslogs require their own interface VPC endpoints.
# No NAT, internet gateway route, public IP, public listener or worker scale-up.
#
# Main VPC route table serves the private subnets implicitly. The public
# subnets have their own explicit aws_route_table.public associations.

variable "research_worker_private_endpoints_enabled" {
  description = "Opt in to hourly-billed private ECR/Logs/Secrets Manager endpoints and S3 gateway for dormant PAPER worker."
  type        = bool
  default     = false

  validation {
    condition     = !var.research_worker_private_endpoints_enabled || var.research_worker_scaffold_enabled
    error_message = "V77 private worker endpoints require the V76 worker scaffold opt-in."
  }
}

locals {
  worker_endpoint_count = var.research_worker_private_endpoints_enabled ? 1 : 0
}

data "aws_prefix_list" "worker_s3" {
  count = local.worker_endpoint_count
  name  = "com.amazonaws.${var.aws_region}.s3"
}

resource "aws_security_group" "research_worker_endpoints" {
  count       = local.worker_endpoint_count
  name        = "${local.name}-research-worker-vpce"
  description = "HTTPS ingress to private AWS service endpoints from worker tasks only"
  vpc_id      = aws_vpc.main.id
  # No inline SG rules: Terraform removes AWS default egress on creation.
  # Explicit ingress is managed by the standalone rule below.
  tags = local.worker_tags
}

# V81 intentionally removes the direct Java-to-worker HTTP ingress.
# All worker HTTP 8090 ingress must originate from the dedicated V81 private
# TLS ALB security group; if private TLS is disabled no ingress is provisioned.

resource "aws_vpc_security_group_ingress_rule" "worker_to_endpoints" {
  count                        = local.worker_endpoint_count
  security_group_id            = aws_security_group.research_worker_endpoints[0].id
  referenced_security_group_id = aws_security_group.research_worker[0].id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "Private worker task to AWS service endpoints only"
}

resource "aws_vpc_security_group_egress_rule" "worker_to_endpoints" {
  count                        = local.worker_endpoint_count
  security_group_id            = aws_security_group.research_worker[0].id
  referenced_security_group_id = aws_security_group.research_worker_endpoints[0].id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "ECR, CloudWatch Logs and Secrets Manager private endpoints"
}

resource "aws_vpc_security_group_egress_rule" "worker_to_s3" {
  count             = local.worker_endpoint_count
  security_group_id = aws_security_group.research_worker[0].id
  prefix_list_id    = data.aws_prefix_list.worker_s3[0].id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "S3 gateway managed-prefix-list for ECR image layer downloads"
}

# The two private subnets use the VPC MAIN route table; attaching the S3
# gateway here does not add a public route to those subnets.
resource "aws_vpc_endpoint" "worker_s3_gateway" {
  count             = local.worker_endpoint_count
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_vpc.main.main_route_table_id]

  lifecycle {
    precondition {
      condition     = var.aws_region == "us-east-1" && data.aws_caller_identity.current.account_id == "816344830716"
      error_message = "V77 endpoints may only target AiTradingSystem 816344830716/us-east-1."
    }
  }
  tags = local.worker_tags
}

resource "aws_vpc_endpoint" "worker_ecr_api" {
  count               = local.worker_endpoint_count
  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.aws_region}.ecr.api"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.research_worker_endpoints[0].id]
  tags                = local.worker_tags
}

resource "aws_vpc_endpoint" "worker_ecr_dkr" {
  count               = local.worker_endpoint_count
  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.aws_region}.ecr.dkr"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.research_worker_endpoints[0].id]
  tags                = local.worker_tags
}

resource "aws_vpc_endpoint" "worker_cloudwatch_logs" {
  count               = local.worker_endpoint_count
  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.aws_region}.logs"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.research_worker_endpoints[0].id]
  tags                = local.worker_tags
}

resource "aws_vpc_endpoint" "worker_secretsmanager" {
  count               = local.worker_endpoint_count
  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${var.aws_region}.secretsmanager"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = aws_subnet.private[*].id
  security_group_ids  = [aws_security_group.research_worker_endpoints[0].id]
  tags                = local.worker_tags
}

# These endpoints only provide AWS service reachability. They do not solve
# Java-to-Python accepted private TLS identity, stable internal DNS, missing model manifests,
# model source rights or operator authorization to run a worker. No task scale.
