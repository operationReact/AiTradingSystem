# V4-09/V76: OPTIONAL DORMANT Python research worker infrastructure.
# Zero resources by default; when explicitly enabled, ECS desired count stays
# ZERO. No deployment, model files, credentials or capital authority.
#
# Deliberately private subnets without internet egress: a later reviewed
# release must add validated private ECR/S3/Logs/Secrets Manager endpoints
# or a cost-reviewed egress architecture before starting any worker.

variable "research_worker_scaffold_enabled" {
  description = "Create only the dormant PAPER research worker resources (desired count remains zero)."
  type        = bool
  default     = false
}

variable "research_worker_image_sha" {
  description = "Full 40-char Git SHA for the worker ECR image; only used when the dormant scaffold is enabled."
  type        = string
  default     = ""

  validation {
    condition     = !var.research_worker_scaffold_enabled || can(regex("^[0-9a-f]{40}$", var.research_worker_image_sha))
    error_message = "Enabled research worker scaffold requires an exact lowercase 40-character Git commit SHA."
  }
}

variable "research_worker_token_secret_arn" {
  description = "Pre-existing AWS Secrets Manager ARN holding an operator-provisioned 32+ char research worker token; never a plaintext value."
  type        = string
  default     = ""

  validation {
    condition     = !var.research_worker_scaffold_enabled || startswith(var.research_worker_token_secret_arn, "arn:aws:secretsmanager:us-east-1:816344830716:secret:")
    error_message = "Enabled research worker scaffold requires a pre-existing token Secrets Manager ARN in the intended AiTradingSystem account/region."
  }
}

locals {
  worker_scaffold_count = var.research_worker_scaffold_enabled ? 1 : 0
  worker_tags = merge(local.tags, {
    RuntimeMode      = "PAPER"
    TradingAuthority = "NONE"
    DeploymentStage  = "DORMANT"
  })
}

resource "aws_ecr_repository" "research_worker" {
  count                = local.worker_scaffold_count
  name                 = "${local.name}-research-worker"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "AES256" }
  tags = local.worker_tags
}

resource "aws_cloudwatch_log_group" "research_worker" {
  count             = local.worker_scaffold_count
  name              = "/ecs/${local.name}/research-worker"
  retention_in_days = 14
  tags              = local.worker_tags
}

resource "aws_security_group" "research_worker" {
  count       = local.worker_scaffold_count
  name        = "${local.name}-research-worker"
  description = "Isolated worker receives HTTP only from optional internal TLS ALB"
  vpc_id      = aws_vpc.main.id

  # Rules are standalone aws_vpc_security_group_*_rule resources.
  # Terraform removes the AWS default ALLOW ALL egress on creation; without
  # the separate V77 endpoint opt-in, no outgoing rule is provisioned.
  tags = local.worker_tags
}

resource "aws_iam_role" "research_worker_execution" {
  count = local.worker_scaffold_count
  name  = "${local.name}-research-worker-execution"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = local.worker_tags
}

resource "aws_iam_role_policy_attachment" "research_worker_execution_managed" {
  count      = local.worker_scaffold_count
  role       = aws_iam_role.research_worker_execution[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "research_worker_token_read" {
  count = local.worker_scaffold_count
  name  = "${local.name}-research-worker-token-read"
  role  = aws_iam_role.research_worker_execution[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [var.research_worker_token_secret_arn]
    }]
  })
}

resource "aws_iam_role" "research_worker_task" {
  count = local.worker_scaffold_count
  name  = "${local.name}-research-worker-task"
  # NO policies, AWS API permissions, broker access or database privileges.
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = local.worker_tags
}

resource "aws_ecs_task_definition" "research_worker" {
  count                    = local.worker_scaffold_count
  family                   = "${local.name}-research-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.research_worker_execution[0].arn
  task_role_arn            = aws_iam_role.research_worker_task[0].arn
  container_definitions = jsonencode([{
    name      = "research-worker"
    image     = "${aws_ecr_repository.research_worker[0].repository_url}:${var.research_worker_image_sha}"
    essential = true
    portMappings = [{
      containerPort = 8090
      hostPort      = 8090
      protocol      = "tcp"
    }]
    environment = [
      { name = "TRADING_MODE", value = "PAPER" },
      { name = "AI_RESEARCH_STRICT_BOOT_REQUIRED", value = "true" },
      { name = "AI_RESEARCH_MODEL_MANIFEST", value = "/opt/research/manifest.json" }
    ]
    secrets = [
      { name = "AI_RESEARCH_WORKER_TOKEN", valueFrom = var.research_worker_token_secret_arn }
    ]
    healthCheck = {
      command = [
        "CMD-SHELL",
        "python -c \"import urllib.request; assert urllib.request.urlopen('http://127.0.0.1:8090/health', timeout=3).status == 200\""
      ]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 30
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.research_worker[0].name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }
  }])

  lifecycle {
    precondition {
      condition     = var.aws_region == "us-east-1" && data.aws_caller_identity.current.account_id == "816344830716"
      error_message = "Research worker scaffold is permitted only in the existing AiTradingSystem account 816344830716/us-east-1."
    }
  }
  tags = local.worker_tags
}

resource "aws_ecs_service" "research_worker" {
  count                  = local.worker_scaffold_count
  name                   = "${local.name}-research-worker"
  cluster                = aws_ecs_cluster.main.id
  task_definition        = aws_ecs_task_definition.research_worker[0].arn
  desired_count          = 0
  launch_type            = "FARGATE"
  platform_version       = "LATEST"
  enable_execute_command          = false
  health_check_grace_period_seconds = 90

  # This is deliberately attached ONLY if the independent V81 cost/TLS opt-in
  # is enabled. In all cases the worker service desired_count remains ZERO.
  dynamic "load_balancer" {
    for_each = var.research_worker_tls_enabled ? [1] : []
    content {
      target_group_arn = aws_lb_target_group.research_worker_tls[0].arn
      container_name   = "research-worker"
      container_port   = 8090
    }
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.research_worker[0].id]
    assign_public_ip = false
  }
  depends_on = [
    aws_iam_role_policy_attachment.research_worker_execution_managed,
    aws_iam_role_policy.research_worker_token_read,
    aws_lb_listener.research_worker_tls
  ]
  tags = local.worker_tags
}

# STOP: leave desiredCount 0 until a separate, independent deployment gate
# validates private networking, real pinned trained artifacts, Java ADMIN
# V72 roster matching, secrets, and licensed India market/news provenance.
