data "aws_caller_identity" "current" {}
data "aws_availability_zones" "available" { state = "available" }

locals {
  name     = "ai-trading-system"
  app_fqdn = var.app_subdomain == "" ? var.domain_name : "${var.app_subdomain}.${var.domain_name}"
  azs      = slice(data.aws_availability_zones.available.names, 0, 2)
  tags = {
    Application = local.name
    Environment = "paper"
    ManagedBy   = "terraform"
  }
}

resource "aws_vpc" "main" {
  cidr_block           = "10.42.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags = merge(local.tags, { Name = "${local.name}-vpc" })
}
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = merge(local.tags, { Name = "${local.name}-igw" })
}
resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.main.id
  availability_zone       = local.azs[count.index]
  cidr_block              = cidrsubnet(aws_vpc.main.cidr_block, 8, count.index + 1)
  map_public_ip_on_launch = true
  tags = merge(local.tags, { Name = "${local.name}-public-${count.index + 1}" })
}
resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  availability_zone = local.azs[count.index]
  cidr_block        = cidrsubnet(aws_vpc.main.cidr_block, 8, count.index + 11)
  tags = merge(local.tags, { Name = "${local.name}-private-${count.index + 1}" })
}
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
  tags = merge(local.tags, { Name = "${local.name}-public-rt" })
}
resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}
resource "aws_db_subnet_group" "main" {
  name       = "${local.name}-db-subnets"
  subnet_ids = aws_subnet.private[*].id
  tags       = local.tags
}

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "Public HTTP and HTTPS ingress"
  vpc_id      = aws_vpc.main.id
  ingress {
    description = "HTTPS"
    from_port = 443
    to_port = 443
    protocol = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "HTTP redirect"
    from_port = 80
    to_port = 80
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
resource "aws_security_group" "tasks" {
  name        = "${local.name}-tasks"
  description = "App traffic only from the load balancer"
  vpc_id      = aws_vpc.main.id
  ingress {
    from_port = 8080
    to_port = 8080
    protocol = "tcp"
    security_groups = [aws_security_group.alb.id]
  }
  egress {
    from_port = 0
    to_port = 0
    protocol = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = local.tags
}
resource "aws_security_group" "database" {
  name        = "${local.name}-database"
  description = "PostgreSQL only from application tasks"
  vpc_id      = aws_vpc.main.id
  ingress {
    from_port = 5432
    to_port = 5432
    protocol = "tcp"
    security_groups = [aws_security_group.tasks.id]
  }
  tags = local.tags
}

resource "aws_ecr_repository" "backend" {
  name                 = "${local.name}-backend"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "AES256" }
  tags = local.tags
}
resource "aws_ecr_repository" "frontend" {
  name                 = "${local.name}-frontend"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "AES256" }
  tags = local.tags
}
resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description = "Keep latest 20 tagged images"
      selection = { tagStatus = "tagged", tagPatternList = ["*"], countType = "imageCountMoreThan", countNumber = 20 }
      action = { type = "expire" }
    }]
  })
}
resource "aws_ecr_lifecycle_policy" "frontend" {
  repository = aws_ecr_repository.frontend.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description = "Keep latest 20 tagged images"
      selection = { tagStatus = "tagged", tagPatternList = ["*"], countType = "imageCountMoreThan", countNumber = 20 }
      action = { type = "expire" }
    }]
  })
}

resource "aws_db_instance" "postgres" {
  identifier                  = "${local.name}-postgres"
  engine                      = "postgres"
    instance_class              = var.db_instance_class
  allocated_storage           = var.db_allocated_storage_gb
  max_allocated_storage       = var.db_max_allocated_storage_gb
  storage_type                = "gp3"
  storage_encrypted           = true
  db_name                     = var.db_name
  username                    = var.db_username
  manage_master_user_password = true
  db_subnet_group_name        = aws_db_subnet_group.main.name
  vpc_security_group_ids      = [aws_security_group.database.id]
  publicly_accessible         = false
  multi_az                    = false
  backup_retention_period     = 7
  deletion_protection         = true
  skip_final_snapshot         = false
  final_snapshot_identifier   = "${local.name}-final"
  auto_minor_version_upgrade  = true
  apply_immediately           = false
  tags = local.tags
}
resource "aws_secretsmanager_secret" "app_api_key" {
  name                    = "${local.name}/app-api-key"
  recovery_window_in_days = 7
  tags = local.tags
}
resource "random_password" "app_api_key" {
  length  = 48
  special = false
}
resource "aws_secretsmanager_secret_version" "app_api_key" {
  secret_id     = aws_secretsmanager_secret.app_api_key.id
  secret_string = random_password.app_api_key.result
}
resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/${local.name}/backend"
  retention_in_days = 30
  tags = local.tags
}
resource "aws_cloudwatch_log_group" "frontend" {
  name              = "/ecs/${local.name}/frontend"
  retention_in_days = 30
  tags = local.tags
}
resource "aws_ecs_cluster" "main" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
  tags = local.tags
}

resource "aws_iam_role" "ecs_execution" {
  name = "${local.name}-ecs-execution"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action = "sts:AssumeRole"
    }]
  })
  tags = local.tags
}
resource "aws_iam_role_policy_attachment" "ecs_execution_managed" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}
resource "aws_iam_role_policy" "ecs_secrets" {
  name = "${local.name}-read-runtime-secrets"
  role = aws_iam_role.ecs_execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["secretsmanager:GetSecretValue"]
      Resource = [
        aws_db_instance.postgres.master_user_secret[0].secret_arn,
        aws_secretsmanager_secret.app_api_key.arn
      ]
    }]
  })
}
resource "aws_iam_role" "ecs_task" {
  name = "${local.name}-ecs-task"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action = "sts:AssumeRole"
    }]
  })
  tags = local.tags
}

resource "aws_lb" "main" {
  name               = "aitrading-paper"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id
  tags = local.tags
}
resource "aws_lb_target_group" "backend" {
  name        = "aitrading-api"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id
  health_check {
    enabled = true
    path = "/api/health"
    matcher = "200"
    interval = 30
    healthy_threshold = 2
    unhealthy_threshold = 3
    timeout = 5
  }
  tags = local.tags
}
resource "aws_lb_target_group" "frontend" {
  name        = "aitrading-web"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id
  health_check {
    enabled = true
    path = "/health"
    matcher = "200"
    interval = 30
    healthy_threshold = 2
    unhealthy_threshold = 3
    timeout = 5
  }
  tags = local.tags
}

resource "aws_route53_zone" "main" {
  name = var.domain_name
  tags = local.tags
}
resource "aws_acm_certificate" "main" {
  domain_name = local.app_fqdn
  subject_alternative_names = local.app_fqdn == var.domain_name ? ["www.${var.domain_name}"] : [var.domain_name, "www.${var.domain_name}"]
  validation_method = "DNS"
  lifecycle { create_before_destroy = true }
  tags = local.tags
}
resource "aws_route53_record" "cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.main.domain_validation_options : dvo.domain_name => {
      name = dvo.resource_record_name
      record = dvo.resource_record_value
      type = dvo.resource_record_type
    }
  }
  allow_overwrite = true
  zone_id = aws_route53_zone.main.zone_id
  name = each.value.name
  type = each.value.type
  ttl = 60
  records = [each.value.record]
}
resource "aws_acm_certificate_validation" "main" {
  count                   = var.enable_https ? 1 : 0
  certificate_arn         = aws_acm_certificate.main.arn
  validation_record_fqdns = [for record in aws_route53_record.cert_validation : record.fqdn]
}
resource "aws_lb_listener" "http_setup" {
  count             = var.enable_https ? 0 : 1
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "HTTPS setup pending. Delegate Route 53 nameservers, then apply with enable_https=true."
      status_code  = "503"
    }
  }
}
resource "aws_lb_listener" "http_redirect" {
  count             = var.enable_https ? 1 : 0
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}
resource "aws_lb_listener" "https" {
  count             = var.enable_https ? 1 : 0
  load_balancer_arn = aws_lb.main.arn
  port = 443
  protocol = "HTTPS"
  certificate_arn = aws_acm_certificate_validation.main[0].certificate_arn
  ssl_policy = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  default_action {
    type = "forward"
    target_group_arn = aws_lb_target_group.frontend.arn
  }
}
resource "aws_lb_listener_rule" "api" {
  count        = var.enable_https ? 1 : 0
  listener_arn = aws_lb_listener.https[0].arn
  priority = 10
  action {
    type = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }
  condition {
    path_pattern { values = ["/api/*"] }
  }
}
resource "aws_route53_record" "app" {
  zone_id = aws_route53_zone.main.zone_id
  name = local.app_fqdn
  type = "A"
  alias {
    name = aws_lb.main.dns_name
    zone_id = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}
resource "aws_route53_record" "www" {
  count = 1
  zone_id = aws_route53_zone.main.zone_id
  name = "www.${var.domain_name}"
  type = "A"
  alias {
    name = aws_lb.main.dns_name
    zone_id = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}

resource "aws_ecs_task_definition" "backend" {
  family = "${local.name}-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode = "awsvpc"
  cpu = var.ecs_cpu
  memory = var.ecs_memory
  execution_role_arn = aws_iam_role.ecs_execution.arn
  task_role_arn = aws_iam_role.ecs_task.arn
  container_definitions = jsonencode([{
    name = "backend"
    image = "${aws_ecr_repository.backend.repository_url}:bootstrap"
    essential = true
    portMappings = [{ containerPort = 8080, hostPort = 8080, protocol = "tcp" }]
    environment = [
      { name = "TRADING_MODE", value = "PAPER" },
      { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/${var.db_name}" },
      { name = "SPRING_DATASOURCE_USERNAME", value = var.db_username },
      { name = "APP_CORS_ALLOWED_ORIGINS", value = "https://${local.app_fqdn},https://www.${var.domain_name}" },
      { name = "NEWS_ANALYSIS_PROVIDER", value = "NONE" }
    ]
    secrets = [
      { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${aws_db_instance.postgres.master_user_secret[0].secret_arn}:password::" },
      { name = "APP_API_KEY", valueFrom = aws_secretsmanager_secret.app_api_key.arn }
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group" = aws_cloudwatch_log_group.backend.name
        "awslogs-region" = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }
  }])
  tags = local.tags
}
resource "aws_ecs_task_definition" "frontend" {
  family = "${local.name}-frontend"
  requires_compatibilities = ["FARGATE"]
  network_mode = "awsvpc"
  cpu = 256
  memory = 512
  execution_role_arn = aws_iam_role.ecs_execution.arn
  task_role_arn = aws_iam_role.ecs_task.arn
  container_definitions = jsonencode([{
    name = "frontend"
    image = "${aws_ecr_repository.frontend.repository_url}:bootstrap"
    essential = true
    portMappings = [{ containerPort = 8080, hostPort = 8080, protocol = "tcp" }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group" = aws_cloudwatch_log_group.frontend.name
        "awslogs-region" = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }
  }])
  tags = local.tags
}
resource "aws_ecs_service" "backend" {
  name = "${local.name}-backend"
  cluster = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.backend.arn
  desired_count = 0
  launch_type = "FARGATE"
  platform_version = "LATEST"
  health_check_grace_period_seconds = 90
  network_configuration {
    subnets = aws_subnet.public[*].id
    security_groups = [aws_security_group.tasks.id]
    assign_public_ip = true
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.backend.arn
    container_name = "backend"
    container_port = 8080
  }
  depends_on = [aws_lb_listener.https, aws_iam_role_policy_attachment.ecs_execution_managed, aws_iam_role_policy.ecs_secrets]
  tags = local.tags
}
resource "aws_ecs_service" "frontend" {
  name = "${local.name}-frontend"
  cluster = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.frontend.arn
  desired_count = 0
  launch_type = "FARGATE"
  platform_version = "LATEST"
  health_check_grace_period_seconds = 60
  network_configuration {
    subnets = aws_subnet.public[*].id
    security_groups = [aws_security_group.tasks.id]
    assign_public_ip = true
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.frontend.arn
    container_name = "frontend"
    container_port = 8080
  }
  depends_on = [aws_lb_listener.https, aws_iam_role_policy_attachment.ecs_execution_managed]
  tags = local.tags
}

resource "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
  tags = local.tags
}
resource "aws_iam_role" "github_deploy" {
  name = "${local.name}-github-deploy"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = { "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com" }
        StringLike = { "token.actions.githubusercontent.com:sub" = "repo:${var.allowed_github_repository}:ref:refs/heads/${var.allowed_github_branch}" }
      }
    }]
  })
  tags = local.tags
}
resource "aws_iam_role_policy" "github_deploy" {
  name = "${local.name}-github-deploy"
  role = aws_iam_role.github_deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"], Resource = [aws_ecr_repository.backend.arn, aws_ecr_repository.frontend.arn] },
      { Effect = "Allow", Action = ["ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition", "ecs:DescribeServices", "ecs:UpdateService"], Resource = "*" },
      { Effect = "Allow", Action = ["iam:PassRole"], Resource = [aws_iam_role.ecs_execution.arn, aws_iam_role.ecs_task.arn] }
    ]
  })
  depends_on = [aws_ecs_service.backend, aws_ecs_service.frontend]
}
