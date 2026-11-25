output "cognito_user_pool_id" {
  description = "Public User Pool identifier for Flutter build configuration."
  value       = aws_cognito_user_pool.minttick.id
}

output "cognito_client_id" {
  description = "Public client ID. A mobile/web app client never has a secret."
  value       = aws_cognito_user_pool_client.flutter.id
}

output "cognito_region" {
  value = var.aws_region
}

output "cognito_issuer" {
  description = "Exact OIDC issuer for Spring Boot JWT verification."
  value       = "https://cognito-idp.${var.aws_region}.amazonaws.com/${aws_cognito_user_pool.minttick.id}"
}
