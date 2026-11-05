# Non-secret, dedicated S3 state backend for the PAPER EC2 root only.
# Bootstrap via CloudFormation FIRST. Never reuse the older ECS/RDS state.
bucket       = "aitrading-paper-tfstate-098859530205-ap-south-1"
key          = "ai-trading/paper-ec2/terraform.tfstate"
region       = "ap-south-1"
encrypt      = true
use_lockfile = true
