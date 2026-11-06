terraform {
  required_version = ">= 1.10.0, < 2.0.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.90"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.7"
    }
  }
  backend "s3" {}
}

provider "aws" {
  region              = "ap-south-1"
  allowed_account_ids = ["098859530205"]
  default_tags {
    tags = {
      Application = "MintTick"
      Environment = "dev"
      ManagedBy   = "terraform"
      Role        = "read-only-market-data"
    }
  }
}
