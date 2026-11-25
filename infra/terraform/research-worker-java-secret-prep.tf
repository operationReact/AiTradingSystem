# V4-09/V82: disabled-by-default Java backend secret/HTTPS configuration.
# This module only PREPARES a private worker URL/token for the Java backend.
# Even if enabled by an operator, BOTH research flags remain false and there
# is no Java-to-Python request, ECR push, deployment or LIVE/capital authority.

variable "research_worker_java_secret_prep_enabled" {
  type        = bool
  default     = false
  description = "Explicitly prepare the backend task HTTPS URL and Secrets Manager worker token without enabling Java inference/readiness."

  validation {
    condition = !var.research_worker_java_secret_prep_enabled || (
      var.research_worker_tls_enabled &&
      var.research_worker_scaffold_enabled &&
      var.research_worker_private_endpoints_enabled &&
      var.enable_https &&
      var.aws_region == "us-east-1"
    )
    error_message = "V82 Java worker secret preparation requires explicitly enabled V76/V77/V81 private HTTPS prerequisites in us-east-1."
  }
}

locals {
  worker_java_secret_prep_count = var.research_worker_java_secret_prep_enabled ? 1 : 0
}

# Do NOT add the research token to the existing aws_iam_role.ecs_execution.
# That role is shared by public frontend and backend. A dedicated backend
# ECS execution role only exists when V82 is operator-enabled.
resource "aws_iam_role" "worker_java_backend_execution" {
  count = local.worker_java_secret_prep_count
  name  = "${local.name}-backend-worker-execution"
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

resource "aws_iam_role_policy_attachment" "worker_java_backend_execution_managed" {
  count      = local.worker_java_secret_prep_count
  role       = aws_iam_role.worker_java_backend_execution[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "worker_java_backend_secrets" {
  count = local.worker_java_secret_prep_count
  name  = "${local.name}-backend-validated-worker-secrets"
  role  = aws_iam_role.worker_java_backend_execution[0].id

  # The ECS execution agent fetches the token into the backend container.
  # ECS task role receives no new IAM policies. The same exact token secret
  # ARN is already required by the separate Python worker task definition.
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["secretsmanager:GetSecretValue"]
      Resource = [
        aws_db_instance.postgres.master_user_secret[0].secret_arn,
        aws_secretsmanager_secret.app_api_key.arn,
        var.research_worker_token_secret_arn
      ]
    }]
  })
}

output "research_worker_java_secret_preparation_enabled" {
  description = "Metadata only: whether the Java backend ECS task is configured with the private HTTPS URL and worker token reference; no readiness/dispatch implied."
  value       = var.research_worker_java_secret_prep_enabled
}
