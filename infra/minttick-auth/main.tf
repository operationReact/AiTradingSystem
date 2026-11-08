variable "aws_region" {
  type        = string
  description = "Cognito User Pool region. Keep this aligned with API JWT issuer validation."
  default     = "ap-south-1"
}

variable "environment" {
  type        = string
  default     = "dev"
  description = "Keep dev/prod separate to avoid mixing customer identities."
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging or prod."
  }
}

resource "aws_cognito_user_pool" "minttick" {
  name                = "minttick-${var.environment}-customer-users"
  user_pool_tier      = "ESSENTIALS"
  deletion_protection = "ACTIVE"

  # Username is the app login name; users may also sign in with their verified email.
  username_configuration {
    case_sensitive = false
  }

  alias_attributes         = ["email"]
  auto_verified_attributes = ["email"]

  # Use long passphrases: avoid brittle forced character classes.
  password_policy {
    minimum_length                   = 15
    require_lowercase                = false
    require_uppercase                = false
    require_numbers                  = false
    require_symbols                  = false
    temporary_password_validity_days = 3
  }

  schema {
    name                = "email"
    attribute_data_type = "String"
    required            = true
    mutable             = true
    string_attribute_constraints {
      min_length = "3"
      max_length = "320"
    }
  }

  verification_message_template {
    default_email_option = "CONFIRM_WITH_CODE"
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  # TOTP is available for a later security-settings rollout; do not force
  # enrollment until the Flutter step-up/MFA screens are integrated.
  mfa_configuration = "OPTIONAL"
  software_token_mfa_configuration {
    enabled = true
  }

  admin_create_user_config {
    allow_admin_create_user_only = false
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_cognito_user_pool_client" "flutter" {
  name         = "minttick-${var.environment}-flutter-public-client"
  user_pool_id = aws_cognito_user_pool.minttick.id

  # A public mobile/web client must never contain a client secret.
  generate_secret               = false
  prevent_user_existence_errors = "ENABLED"
  enable_token_revocation       = true

  explicit_auth_flows = [
    "ALLOW_USER_SRP_AUTH",
    # Private dev Flutter Chrome can explicitly opt into USER_PASSWORD_AUTH
    # to diagnose an SRP request that stalls before reaching Cognito.
    # Cognito continues to validate passwords; SDK uses TLS. This app client
    # already has generate_secret=false; never export any broker credentials.
    "ALLOW_USER_PASSWORD_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH",
  ]

  access_token_validity  = 15
  id_token_validity      = 15
  refresh_token_validity = 3
  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }

  lifecycle {
    prevent_destroy = true
  }
}
