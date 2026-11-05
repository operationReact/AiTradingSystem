data "aws_caller_identity" "current" {}
data "aws_ssm_parameter" "al2023_arm64" {
  count = local.enabled ? 1 : 0
  name  = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-arm64"
}

locals {
  enabled   = var.approve_paid_provision == "PROVISION_PAPER_EC2_4GB"
  name      = "aitradingsystem-paper-ec2"
  stop_name = "aitrading-paper-nightly-stop"
  tags = {
    Name       = local.name
    CostCenter = "ai-trading-paper"
  }
}

resource "aws_vpc" "paper" {
  count                = local.enabled ? 1 : 0
  cidr_block           = "10.87.0.0/24"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = local.tags
}
resource "aws_internet_gateway" "paper" {
  count  = local.enabled ? 1 : 0
  vpc_id = aws_vpc.paper[0].id
  tags   = local.tags
}
resource "aws_subnet" "paper" {
  count                   = local.enabled ? 1 : 0
  vpc_id                  = aws_vpc.paper[0].id
  cidr_block              = "10.87.0.0/26"
  map_public_ip_on_launch = false
  tags                    = local.tags
}
resource "aws_route_table" "paper" {
  count  = local.enabled ? 1 : 0
  vpc_id = aws_vpc.paper[0].id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.paper[0].id
  }
  tags = local.tags
}
resource "aws_route_table_association" "paper" {
  count          = local.enabled ? 1 : 0
  subnet_id      = aws_subnet.paper[0].id
  route_table_id = aws_route_table.paper[0].id
}
resource "aws_security_group" "paper" {
  count       = local.enabled ? 1 : 0
  name_prefix = "aitrading-paper-no-inbound-"
  description = "No inbound: SSM access only. No SSH/HTTP/PostgreSQL/research worker exposure."
  vpc_id      = aws_vpc.paper[0].id
  # No ingress rules.  No ALB or NAT Gateway. Outbound required for SSM, package setup.
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = local.tags
}

resource "aws_iam_role" "instance_ssm" {
  count = local.enabled ? 1 : 0
  name  = "aitrading-paper-ec2-ssm"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
  tags = local.tags
}
resource "aws_iam_role_policy_attachment" "ssm_core" {
  count      = local.enabled ? 1 : 0
  role       = aws_iam_role.instance_ssm[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}
resource "aws_iam_instance_profile" "paper" {
  count = local.enabled ? 1 : 0
  name  = "aitrading-paper-ec2-ssm"
  role  = aws_iam_role.instance_ssm[0].name
}

resource "aws_instance" "paper" {
  count                                = local.enabled ? 1 : 0
  ami                                  = data.aws_ssm_parameter.al2023_arm64[0].value
  instance_type                        = var.instance_type
  subnet_id                            = aws_subnet.paper[0].id
  vpc_security_group_ids               = [aws_security_group.paper[0].id]
  associate_public_ip_address          = true
  iam_instance_profile                 = aws_iam_instance_profile.paper[0].name
  user_data                            = file("${path.module}/bootstrap.sh")
  user_data_replace_on_change          = false
  disable_api_termination              = true
  instance_initiated_shutdown_behavior = "stop"
  monitoring                           = false
  ebs_optimized                        = true
  credit_specification {
    cpu_credits = "standard"
  }
  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
  }
  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_gib
    encrypted             = true
    delete_on_termination = false
  }
  lifecycle {
    prevent_destroy = true
  }
  tags       = local.tags
  depends_on = [aws_route_table_association.paper, aws_iam_role_policy_attachment.ssm_core]
}

resource "aws_iam_role" "daily_stop" {
  count = local.enabled ? 1 : 0
  name  = "aitrading-paper-daily-stop"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "scheduler.amazonaws.com" }
      Condition = {
        StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
        ArnEquals = {
          "aws:SourceArn" = "arn:aws:scheduler:${var.aws_region}:${data.aws_caller_identity.current.account_id}:schedule/default/${local.stop_name}"
        }
      }
    }]
  })
  tags = local.tags
}
resource "aws_iam_role_policy" "daily_stop" {
  count = local.enabled ? 1 : 0
  name  = "only-stop-aitrading-paper"
  role  = aws_iam_role.daily_stop[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "ec2:StopInstances"
      Resource = aws_instance.paper[0].arn
      Condition = {
        StringEquals = {
          "ec2:ResourceTag/Application" = "ai-trading-system"
          "ec2:ResourceTag/Environment" = "paper"
        }
      }
    }]
  })
}

resource "aws_scheduler_schedule" "daily_stop" {
  count                        = local.enabled ? 1 : 0
  name                         = local.stop_name
  schedule_expression          = "cron(0 ${var.daily_stop_hour_ist} * * ? *)"
  schedule_expression_timezone = "Asia/Kolkata"
  state                        = var.daily_stop_enabled ? "ENABLED" : "DISABLED"
  flexible_time_window {
    mode = "OFF"
  }
  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ec2:stopInstances"
    role_arn = aws_iam_role.daily_stop[0].arn
    input    = jsonencode({ InstanceIds = [aws_instance.paper[0].id] })
    retry_policy {
      maximum_retry_attempts       = 3
      maximum_event_age_in_seconds = 3600
    }
  }
  depends_on = [aws_iam_role_policy.daily_stop]
}
