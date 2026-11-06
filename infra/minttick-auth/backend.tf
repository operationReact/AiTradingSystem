terraform {
  # This bucket already backs the PAPER EC2 stack. Its actual AWS region is
  # us-east-1 despite ap-south-1 appearing in the bucket name.
  backend "s3" {
    bucket       = "aitrading-paper-tfstate-098859530205-ap-south-1"
    key          = "ai-trading/minttick-auth/dev/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
