variable "enable_paper_invoke" {
  description = "Opt in: grant existing PAPER instance role InvokeFunction only, never SSM credential access."
  type        = bool
  default     = false
}

variable "ssm_customer_kms_key_arn" {
  description = "Optional customer-managed KMS key ARN for SecureStrings; empty for AWS managed aws/ssm."
  type        = string
  default     = ""

  validation {
    condition     = var.ssm_customer_kms_key_arn == "" || can(regex("^arn:aws:kms:ap-south-1:098859530205:key/[a-zA-Z0-9-]+$", var.ssm_customer_kms_key_arn))
    error_message = "KMS key must be in the intended account and Mumbai region."
  }
}
