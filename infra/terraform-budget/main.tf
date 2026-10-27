data "aws_caller_identity" "current" {}
data "aws_vpc" "default" { default = true }
data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}
data "aws_subnet" "selected" { id = sort(data.aws_subnets.default.ids)[0] }
data "aws_ami" "al2023_arm64" {
  most_recent = true
  owners = ["amazon"]
  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-kernel-6.1-arm64"]
  }
  filter {
    name   = "architecture"
    values = ["arm64"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
  filter {
    name   = "root-device-type"
    values = ["ebs"]
  }
}
locals {
  name = "ai-trading-paper"
  tags = {
    Application = "AiTradingSystem"
    Environment = "paper"
    ManagedBy = "terraform"
    CostProfile = "budget-25"
  }
  secret_payload = {
    dbPassword = random_password.db.result
    appApiKey = random_password.api_key.result
  }
}
resource "random_password" "db" {
  length = 32
  special = false
}
resource "random_password" "api_key" {
  length = 48
  special = false
}
resource "aws_secretsmanager_secret" "runtime" {
  name = "${local.name}/runtime-secrets"
  recovery_window_in_days = 7
  tags = local.tags
}
resource "aws_secretsmanager_secret_version" "runtime" {
  secret_id = aws_secretsmanager_secret.runtime.id
  secret_string = jsonencode(local.secret_payload)
}
resource "aws_ecr_repository" "backend" {
  name = "ai-trading-system-backend"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "AES256" }
  tags = local.tags
}
resource "aws_ecr_repository" "frontend" {
  name = "ai-trading-system-frontend"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "AES256" }
  tags = local.tags
}
resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name
  policy = jsonencode({ rules = [{ rulePriority = 1, description = "Keep latest 10 images", selection = { tagStatus = "tagged", tagPatternList = ["*"], countType = "imageCountMoreThan", countNumber = 10 }, action = { type = "expire" } }] })
}
resource "aws_ecr_lifecycle_policy" "frontend" {
  repository = aws_ecr_repository.frontend.name
  policy = jsonencode({ rules = [{ rulePriority = 1, description = "Keep latest 10 images", selection = { tagStatus = "tagged", tagPatternList = ["*"], countType = "imageCountMoreThan", countNumber = 10 }, action = { type = "expire" } }] })
}
resource "aws_s3_bucket" "backups" {
  bucket = "${local.name}-db-backups-${data.aws_caller_identity.current.account_id}"
  force_destroy = false
  tags = local.tags
}
resource "aws_s3_bucket_public_access_block" "backups" {
  bucket = aws_s3_bucket.backups.id
  block_public_acls = true
  block_public_policy = true
  ignore_public_acls = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_server_side_encryption_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}
resource "aws_s3_bucket_lifecycle_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id
  rule {
    id = "expire-old-database-backups"
    status = "Enabled"
    filter { prefix = "daily/" }
    expiration { days = 7 }
  }
}
resource "aws_security_group" "instance" {
  name = "${local.name}-sg"
  description = "Public web ports only; administration through Systems Manager"
  vpc_id = data.aws_vpc.default.id
  ingress {
    description = "HTTP for automatic HTTPS setup"
    from_port = 80
    to_port = 80
    protocol = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "HTTPS"
    from_port = 443
    to_port = 443
    protocol = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port = 0
    to_port = 0
    protocol = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = local.tags
}
resource "aws_iam_role" "instance" {
  name = "${local.name}-instance-role"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" }, Action = "sts:AssumeRole" }] })
  tags = local.tags
}
resource "aws_iam_role_policy_attachment" "ssm" {
  role = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}
resource "aws_iam_role_policy" "instance_access" {
  name = "${local.name}-runtime-access"
  role = aws_iam_role.instance.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
    { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"], Resource = [aws_ecr_repository.backend.arn, aws_ecr_repository.frontend.arn] },
    { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = [aws_secretsmanager_secret.runtime.arn] },
    { Effect = "Allow", Action = ["s3:PutObject", "s3:AbortMultipartUpload"], Resource = ["${aws_s3_bucket.backups.arn}/daily/*"] }
  ] })
}
resource "aws_iam_instance_profile" "instance" {
  name = "${local.name}-instance-profile"
  role = aws_iam_role.instance.name
}
resource "aws_instance" "app" {
  ami = data.aws_ami.al2023_arm64.id
  instance_type = "t4g.small"
  subnet_id = data.aws_subnet.selected.id
  vpc_security_group_ids = [aws_security_group.instance.id]
  iam_instance_profile = aws_iam_instance_profile.instance.name
  associate_public_ip_address = true
  monitoring = false
  ebs_optimized = true
  root_block_device {
    volume_type = "gp3"
    volume_size = 20
    encrypted = true
    delete_on_termination = false
  }
  user_data = templatefile("${path.module}/bootstrap.sh.tftpl", {
    region = var.aws_region
    domain_name = var.domain_name
    secret_arn = aws_secretsmanager_secret.runtime.arn
    backend_image = aws_ecr_repository.backend.repository_url
    frontend_image = aws_ecr_repository.frontend.repository_url
    backup_bucket = aws_s3_bucket.backups.bucket
    account_id = data.aws_caller_identity.current.account_id
  })
  user_data_replace_on_change = true
  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
  }
  tags = merge(local.tags, { Name = local.name })
  lifecycle {
    precondition {
      condition = length(data.aws_subnets.default.ids) > 0
      error_message = "The selected region/account must have a default VPC and at least one subnet."
    }
  }
}
resource "aws_eip" "app" {
  domain = "vpc"
  tags = merge(local.tags, { Name = "${local.name}-eip" })
}
resource "aws_eip_association" "app" {
  instance_id = aws_instance.app.id
  allocation_id = aws_eip.app.id
}
resource "aws_route53_zone" "main" {
  name = var.domain_name
  tags = local.tags
}
resource "aws_route53_record" "apex" {
  zone_id = aws_route53_zone.main.zone_id
  name = var.domain_name
  type = "A"
  ttl = 300
  records = [aws_eip.app.public_ip]
}
resource "aws_route53_record" "www" {
  zone_id = aws_route53_zone.main.zone_id
  name = "www.${var.domain_name}"
  type = "A"
  ttl = 300
  records = [aws_eip.app.public_ip]
}
resource "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
  tags = local.tags
}
resource "aws_iam_role" "github_deploy" {
  name = "${local.name}-github-deploy"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Effect = "Allow"
    Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
    Action = "sts:AssumeRoleWithWebIdentity"
    Condition = {
      StringEquals = { "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com" }
      StringLike = { "token.actions.githubusercontent.com:sub" = "repo:${var.allowed_github_repository}:ref:refs/heads/${var.allowed_github_branch}" }
    }
  }] })
  tags = local.tags
}
resource "aws_iam_role_policy" "github_deploy" {
  name = "${local.name}-github-deploy"
  role = aws_iam_role.github_deploy.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
    { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage"], Resource = [aws_ecr_repository.backend.arn, aws_ecr_repository.frontend.arn] },
    { Effect = "Allow", Action = ["ssm:SendCommand"], Resource = ["arn:aws:ssm:${var.aws_region}::document/AWS-RunShellScript", aws_instance.app.arn] },
    { Effect = "Allow", Action = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"], Resource = "*" }
  ] })
  depends_on = [aws_instance.app]
}
resource "aws_budgets_budget" "monthly" {
  name = "ai-trading-system-monthly-25-usd"
  budget_type = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit = "USD"
  time_unit = "MONTHLY"
  cost_types {
    include_credit = false
    include_discount = true
    include_other_subscription = true
    include_recurring = true
    include_refund = false
    include_subscription = true
    include_support = true
    include_tax = true
    use_amortized = false
    use_blended = false
  }
  notification {
    comparison_operator = "GREATER_THAN"
    notification_type = "ACTUAL"
    threshold = 80
    threshold_type = "PERCENTAGE"
    subscriber_email_addresses = [var.budget_alert_email]
  }
  notification {
    comparison_operator = "GREATER_THAN"
    notification_type = "ACTUAL"
    threshold = 100
    threshold_type = "PERCENTAGE"
    subscriber_email_addresses = [var.budget_alert_email]
  }
  notification {
    comparison_operator = "GREATER_THAN"
    notification_type = "FORECASTED"
    threshold = 100
    threshold_type = "PERCENTAGE"
    subscriber_email_addresses = [var.budget_alert_email]
  }
  tags = local.tags
}
