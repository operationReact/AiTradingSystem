variable "approval" {
  type        = string
  default     = ""
  description = "Explicit opt-in to the paid, always-on pilot (EC2, EBS, public IPv4)."
  validation {
    condition     = contains(["", "PROVISION_MINTTICK_ACCOUNT_DEV"], var.approval)
    error_message = "Only the explicit dev provision approval is accepted."
  }
}
variable "hosted_zone_id" {
  type        = string
  default     = ""
  description = "Existing public Route53 hosted zone; no zone created or delegated here."
}
variable "account_domain" {
  type        = string
  default     = ""
  description = "Owned DNS hostname (e.g. accounts.example.com); Caddy obtains HTTPS certificate."
  validation {
    condition = var.account_domain == "" || (
      can(regex("^[a-z0-9][a-z0-9.-]*[a-z0-9]$", var.account_domain))
      && length(var.account_domain) <= 253
    )
    error_message = "Provide a plain lowercase DNS hostname."
  }
}
variable "instance_type" {
  type    = string
  default = "t4g.small"
  validation {
    condition     = var.instance_type == "t4g.small"
    error_message = "Only the 2 GiB ARM64 pilot instance is allowed."
  }
}
