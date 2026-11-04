# V4-09 / V81: optional internal HTTPS-only entry point for the dormant PAPER
# Python research worker. Disabled unless an operator explicitly opts in.
# No external ALB, no worker scale-up, no Java dispatch or trading authority.
#
# Java HttpClient connects to a public-CA-verifiable HTTPS hostname resolved
# privately in this VPC. ALB terminates TLS and proxies HTTP on 8090 to a
# private ECS task. This is NOT mTLS or end-to-end TLS to the container.
#
# Public DNS is used only for ACM domain-control validation. The A record for
# the research service exists in a VPC-associated Route53 PRIVATE hosted zone.

variable "research_worker_private_tls_enabled" {
  description = "Cost-bearing opt-in: create a private HTTPS ALB and DNS for a dormant PAPER research worker."
  type        = bool
  default     = false

  validation {
    condition = !var.research_worker_private_tls_enabled || (
      var.research_worker_scaffold_enabled && var.research_worker_private_endpoints_enabled
    )
    error_message = "V81 private TLS requires separately enabled V76 worker scaffold and V77 worker endpoints."
  }
}

locals {
  worker_tls_count       = var.research_worker_private_tls_enabled ? 1 : 0
  worker_tls_zone_domain = "internal.${var.domain_name}"
  worker_tls_fqdn        = "research.${local.worker_tls_zone_domain}"
}

# This additional backend SG labels *only* Java backend ECS tasks.
# Existing aws_security_group.tasks is shared by Java and Flutter; therefore
# using the shared SG as an ALB caller would wrongly authorize the frontend.
resource "aws_security_group" "research_worker_java_identity" {
  count       = local.worker_tls_count
  name        = "${local.name}-worker-java-identity"
  description = "Backend-only identity SG for access to private worker TLS endpoint"
  vpc_id      = aws_vpc.main.id
  tags        = local.worker_tags
}

resource "aws_security_group" "research_worker_tls_alb" {
  count       = local.worker_tls_count
  name        = "${local.name}-worker-private-tls"
  description = "Internal worker HTTPS ALB; only backend ECS identity SG can connect"
  vpc_id      = aws_vpc.main.id
  tags        = local.worker_tags
}

# Do not mix inline and standalone SG rules. The ALB does not accept 80/TCP
# or traffic from the internet, shared backend/frontend group or arbitrary VPC.
resource "aws_vpc_security_group_ingress_rule" "research_worker_tls_from_backend" {
  count                        = local.worker_tls_count
  security_group_id            = aws_security_group.research_worker_tls_alb[0].id
  referenced_security_group_id = aws_security_group.research_worker_java_identity[0].id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "HTTPS only from dedicated backend task identity SG"
}

resource "aws_vpc_security_group_egress_rule" "research_worker_tls_to_worker" {
  count                        = local.worker_tls_count
  security_group_id            = aws_security_group.research_worker_tls_alb[0].id
  referenced_security_group_id = aws_security_group.research_worker[0].id
  ip_protocol                  = "tcp"
  from_port                    = 8090
  to_port                      = 8090
  description                  = "HTTP only to private Python worker targets after ALB TLS termination"
}

resource "aws_vpc_security_group_ingress_rule" "research_worker_from_tls_alb" {
  count                        = local.worker_tls_count
  security_group_id            = aws_security_group.research_worker[0].id
  referenced_security_group_id = aws_security_group.research_worker_tls_alb[0].id
  ip_protocol                  = "tcp"
  from_port                    = 8090
  to_port                      = 8090
  description                  = "Only internal HTTPS ALB may reach worker container HTTP"
}

resource "aws_lb" "research_worker_private_tls" {
  count              = local.worker_tls_count
  name               = "aitrading-worker-private"
  internal           = true
  load_balancer_type = "application"
  security_groups    = [aws_security_group.research_worker_tls_alb[0].id]
  subnets            = aws_subnet.private[*].id
  ip_address_type    = "ipv4"
  tags               = local.worker_tags

  lifecycle {
    precondition {
      condition = var.aws_region == "us-east-1" && (
        data.aws_caller_identity.current.account_id == "816344830716"
      )
      error_message = "Worker private TLS can only be provisioned to AiTradingSystem 816344830716/us-east-1."
    }
  }
}

resource "aws_lb_target_group" "research_worker_private_tls" {
  count                = local.worker_tls_count
  name                 = "aitrading-worker"
  port                 = 8090
  protocol             = "HTTP"
  protocol_version     = "HTTP1"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 30
  health_check {
    enabled             = true
    protocol            = "HTTP"
    path                = "/health"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
  tags = local.worker_tags
}

# The certificate's hostname is NOT publicly routable. The validation CNAME
# must nonetheless be publicly resolvable under the already-delegated domain
# before ACM can issue the public-CA-trusted certificate for Java.
resource "aws_acm_certificate" "research_worker_private_tls" {
  count             = local.worker_tls_count
  domain_name       = local.worker_tls_fqdn
  validation_method = "DNS"
  tags              = local.worker_tags
  lifecycle { create_before_destroy = true }
}

resource "aws_route53_record" "research_worker_tls_validation" {
  for_each = var.research_worker_private_tls_enabled ? {
    for dvo in aws_acm_certificate.research_worker_private_tls[0].domain_validation_options :
    dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  } : {}
  allow_overwrite = true
  zone_id         = aws_route53_zone.main.zone_id
  name            = each.value.name
  type            = each.value.type
  ttl             = 60
  records         = [each.value.record]
}

resource "aws_acm_certificate_validation" "research_worker_private_tls" {
  count                   = local.worker_tls_count
  certificate_arn         = aws_acm_certificate.research_worker_private_tls[0].arn
  validation_record_fqdns = [
    for record in aws_route53_record.research_worker_tls_validation : record.fqdn
  ]
}

resource "aws_lb_listener" "research_worker_private_tls" {
  count             = local.worker_tls_count
  load_balancer_arn = aws_lb.research_worker_private_tls[0].arn
  port              = 443
  protocol          = "HTTPS"
  certificate_arn   = aws_acm_certificate_validation.research_worker_private_tls[0].certificate_arn
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"

  # The only allowed API paths are listed below; no public health/metrics,
  # OpenAPI, catch-all proxying or HTTP listener is exposed via the TLS ALB.
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}

resource "aws_lb_listener_rule" "research_worker_private_research_only" {
  count        = local.worker_tls_count
  listener_arn = aws_lb_listener.research_worker_private_tls[0].arn
  priority     = 10
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.research_worker_private_tls[0].arn
  }
  condition {
    path_pattern {
      values = [
        "/v1/research/artifact-readiness",
        "/v1/research/artifact-inference"
      ]
    }
  }
}

resource "aws_route53_zone" "research_worker_private" {
  count = local.worker_tls_count
  name  = local.worker_tls_zone_domain
  vpc { vpc_id = aws_vpc.main.id }
  tags = local.worker_tags
}

resource "aws_route53_record" "research_worker_private" {
  count   = local.worker_tls_count
  zone_id = aws_route53_zone.research_worker_private[0].zone_id
  name    = local.worker_tls_fqdn
  type    = "A"
  alias {
    name                   = aws_lb.research_worker_private_tls[0].dns_name
    zone_id                = aws_lb.research_worker_private_tls[0].zone_id
    # A dormant worker has no healthy targets. DNS remains discoverable for
    # planned future preflight; alias resolution never means runtime ready.
    evaluate_target_health = false
  }
}

output "research_worker_private_https_url" {
  description = "Private VPC-only Java worker HTTPS URL; not proof of worker readiness."
  value       = var.research_worker_private_tls_enabled ? "https://${local.worker_tls_fqdn}" : null
}
