terraform {
  backend "s3" {
    key          = "minttick/customer-auth/dev/terraform.tfstate"
    region       = "ap-south-1"
    encrypt      = true
    use_lockfile = true
  }
}
