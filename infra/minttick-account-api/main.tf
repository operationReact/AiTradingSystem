locals {
  # Fail closed without explicit cost approval AND a DNS ownership target.\n  enabled = var.approval == "PROVISION_MINTTICK_ACCOUNT_DEV" && var.hosted_zone_id != "" && var.account_domain != ""
  tags = {
    Name        = "minttick-dev-account-api"
    Application = "MintTick"
    Environment = "dev"
    Component   = "AccountAPI"
  }
}

data "aws_ssm_parameter" "arm_ami" {
  count = local.enabled ? 1 : 0
  name  = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-arm64"
}

resource "aws_vpc" "account" {
  count                = local.enabled ? 1 : 0
  cidr_block           = "10.91.0.0/24"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = local.tags
}
resource "aws_subnet" "account" {
  count                   = local.enabled ? 1 : 0
  vpc_id                  = aws_vpc.account[0].id
  cidr_block              = "10.91.0.0/26"
  map_public_ip_on_launch = false
  tags                    = local.tags
}
resource "aws_internet_gateway" "account" {
  count  = local.enabled ? 1 : 0
  vpc_id = aws_vpc.account[0].id
  tags   = local.tags
}
resource "aws_route_table" "account" {
  count  = local.enabled ? 1 : 0
  vpc_id = aws_vpc.account[0].id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.account[0].id
  }
  tags = local.tags
}
resource "aws_route_table_association" "account" {
  count          = local.enabled ? 1 : 0
  subnet_id      = aws_subnet.account[0].id
  route_table_id = aws_route_table.account[0].id
}
resource "aws_security_group" "account" {
  count       = local.enabled ? 1 : 0
  name_prefix = "minttick-account-api-"
  description = "Only public HTTP challenge/HTTPS; no SSH, database or app port ingress"
  vpc_id      = aws_vpc.account[0].id
  ingress {
    description = "ACME HTTP challenge and redirect"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "HTTPS customer account API"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = local.tags
}
resource "aws_iam_role" "instance" {
  count = local.enabled ? 1 : 0
  name  = "minttick-dev-account-ssm"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = "sts:AssumeRole"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
  tags = local.tags
}
resource "aws_iam_role_policy_attachment" "ssm" {
  count      = local.enabled ? 1 : 0
  role       = aws_iam_role.instance[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}
resource "aws_iam_instance_profile" "account" {
  count = local.enabled ? 1 : 0
  name  = "minttick-dev-account-ssm"
  role  = aws_iam_role.instance[0].name
}
resource "aws_ecr_repository" "backend" {
  count                = local.enabled ? 1 : 0
  name                 = "minttick-dev-account-api"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "AES256"
  }
  lifecycle {
    prevent_destroy = true
  }
  tags = local.tags
}
resource "aws_iam_role_policy" "pull" {
  count = local.enabled ? 1 : 0
  name  = "minttick-dev-read-account-image"
  role  = aws_iam_role.instance[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"]
        Resource = aws_ecr_repository.backend[0].arn
      }
    ]
  })
}
resource "aws_instance" "account" {
  count                       = local.enabled ? 1 : 0
  ami                         = data.aws_ssm_parameter.arm_ami[0].value
  instance_type               = var.instance_type
  subnet_id                   = aws_subnet.account[0].id
  vpc_security_group_ids      = [aws_security_group.account[0].id]
  associate_public_ip_address = false
  iam_instance_profile        = aws_iam_instance_profile.account[0].name
  user_data                   = file("${path.module}/bootstrap.sh")
  user_data_replace_on_change = false
  disable_api_termination     = true
  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
  }
  root_block_device {
    volume_type           = "gp3"
    volume_size           = 30
    encrypted             = true
    delete_on_termination = false
  }
  lifecycle {
    prevent_destroy = true
    precondition {
      condition     = var.hosted_zone_id != "" && var.account_domain != ""
      error_message = "Paid provisioning requires owned account domain and Route53 hosted zone."
    }
  }
  tags = local.tags
  depends_on = [aws_route_table_association.account, aws_iam_role_policy_attachment.ssm]
}
resource "aws_eip" "account" {
  count  = local.enabled ? 1 : 0
  domain = "vpc"
  tags   = local.tags
}
resource "aws_eip_association" "account" {
  count         = local.enabled ? 1 : 0
  allocation_id = aws_eip.account[0].id
  instance_id   = aws_instance.account[0].id
}
resource "aws_route53_record" "account" {
  count   = local.enabled ? 1 : 0
  zone_id = var.hosted_zone_id
  name    = var.account_domain
  type    = "A"
  ttl     = 60
  records = [aws_eip.account[0].public_ip]
  lifecycle {
    prevent_destroy = true
  }
}

check "required_dns_before_paid_apply" {
  assert {
    condition     = !local.enabled || (var.hosted_zone_id != "" && var.account_domain != "")
    error_message = "Provide owned domain and public hosted_zone_id before paid provisioning."
  }
}
