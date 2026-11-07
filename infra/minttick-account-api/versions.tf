terraform {
  required_version = ">= 1.10.0, < 2.0.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.90" }
  }
  # Dedicated key: never share or alter PAPER or Cognito Terraform state.
  backend "s3" {
    bucket       = "aitrading-paper-tfstate-098859530205-ap-south-1"
    key          = "ai-trading/minttick-account-api/dev/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region              = "ap-south-1"
  allowed_account_ids = ["098859530205"]
  default_tags {
    tags = {
      Application = "MintTick"
      Environment = "dev"
      Component   = "AccountAPI"
      ManagedBy   = "Terraform"
    }
  }
}
