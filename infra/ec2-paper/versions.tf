terraform {
  required_version = ">= 1.10.0, < 2.0.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.90" }
  }
  # The state bucket and lock configuration must be created/verified in the
  # correct account BEFORE any apply. Do not use GitHub runner local state.
  backend "s3" {}
}

provider "aws" {
  region              = var.aws_region
  allowed_account_ids = ["098859530205"]
  default_tags {
    tags = {
      Application = "ai-trading-system"
      Environment = "paper"
      ManagedBy   = "terraform-ec2-paper"
      TradingMode = "PAPER"
    }
  }
}
