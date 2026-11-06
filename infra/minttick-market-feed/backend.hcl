# Reuse encrypted S3 backend; isolate state from PAPER EC2.
# Bucket lives in us-east-1 despite its suffix. Never use ephemeral GitHub-runner state.
bucket       = "aitrading-paper-tfstate-098859530205-ap-south-1"
key          = "ai-trading/minttick-market-feed/terraform.tfstate"
region       = "us-east-1"
encrypt      = true
use_lockfile = true
