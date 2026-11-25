# D20-04C REVIEW-ONLY IAM/bucket-policy contract. NO managed resources.
# This root is intentionally not referenced by deployment workflows or
# the live EC2 PAPER Terraform stack.
terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.90"
    }
  }
}

provider "aws" {
  region              = "ap-south-1"
  allowed_account_ids = ["098859530205"]
}
