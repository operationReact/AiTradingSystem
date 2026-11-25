# Non-secret, dedicated S3 state backend for the PAPER EC2 root only.
# Screenshot/CFN evidence: this stack was created in N. Virginia (us-east-1).
# S3 bucket name ends in ap-south-1 but that suffix does NOT determine region.
# AWS provider in versions.tf remains ap-south-1 for eventual Mumbai EC2.
# Never reuse the older ECS/RDS state.
bucket       = "aitrading-paper-tfstate-098859530205-ap-south-1"
key          = "ai-trading/paper-ec2/terraform.tfstate"
region       = "us-east-1"
encrypt      = true
use_lockfile = true
