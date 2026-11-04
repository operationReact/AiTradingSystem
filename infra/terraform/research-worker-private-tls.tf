# V4-09/V81: opt-in, INTERNAL TLS edge for the dormant research worker.
# Default: zero resources, zero listener, zero running ECS tasks.
# Java talks HTTPS to a private Route 53 name with a DNS-validated ACM cert.
# ALB terminates TLS, then forwards HTTP only across a private VPC SG hop.
# No direct Java-to-worker HTTP ingress, public ALB, NAT or LIVE authority.

variable "research_worker_tls_enabled" {
  type        = bool
  default     = false
  description = "Cost-bearing optional internal ACM HTTPS ALB and private Route 53 DNS for the dormant PAPER worker."

  validation {
    condition = !var.research_worker_tls_enabled || (
      var.research_worker_scaffold_enabled &&
      var.research_worker_private_endpoints_enabled &&
      var.enable_https &&
      var.aws_region == "us-east-1"
    )
    error_message = "V81 requires the dormant worker, private AWS endpoints, delegated public-domain HTTPS and the AiTradingSystem us-east-1 region before creating an internal ALB."
  }
}

locals {
  worker_tls_count = var.research_worker_tls_enabled ? 1 : 0
  worker_tls_fqdn  = "research-worker.${var.domain_name}"
}

# ACM publicly validates a certificate for the internal DNS name by a PUBLIC
# parent hosted-zone CNAME; no public A/AAAA record for the worker is created.
resource "aws_acm_certificate" "research_worker_tls" {
  count             = local.worker_tls_count
  domain_name       = local.worker_tls_fqdn
  validation_method = "DNS"
  lifecycle {
    create_before_destroy = true
  }
  tags = local.worker_tags
}

resource "aws_route53_record" "research_worker_tls_validation" {
  count = local.worker_tls_count

  allow_overwrite = true
  zone_id         = aws_route53_zone.main.zone_id
  name            = tolist(aws_acm_certificate.research_worker_tls[0].domain_validation_options)[0].resource_record_name
  type            = tolist(aws_acm_certificate.research_worker_tls[0].domain_validation_options)[0].resource_record_type
  ttl             = 60
  records         = [tolist(aws_acm_certificate.research_worker_tls[0].domain_validation_options)[0].resource_record_value]
}

resource "aws_acm_certificate_validation" "research_worker_tls" {
  count                   = local.worker_tls_count
  certificate_arn         = aws_acm_certificate.research_worker_tls[0].arn
  validation_record_fqdns = [aws_route53_record.research_worker_tls_validation[0].fqdn]
}

# aws_security_group.tasks is shared by Java AND Flutter frontend.
# Give only Java backend ECS tasks a distinct caller identity SG, with no
# ingress or egress permissions of its own. Existing task egress is unchanged.
resource "aws_security_group" "research_worker_java_identity" {
  count       = local.worker_tls_count
  name        = "${local.name}-worker-java-identity"
  description = "Dedicated Java backend ECS identity for private research HTTPS"
  vpc_id      = aws_vpc.main.id
  tags        = local.worker_tags
}

resource "aws_security_group" "research_worker_tls_alb" {
  count       = local.worker_tls_count
  name        = "${local.name}-worker-https-alb"
  description = "Internal HTTPS ALB, backend tasks only; no public network rules"
  vpc_id      = aws_vpc.main.id
  # All ingress/egress rules are separately managed, not inline.
  tags = local.worker_tags
}

resource "aws_vpc_security_group_ingress_rule" "backend_to_worker_tls" {
  count                        = local.worker_tls_count
  security_group_id            = aws_security_group.research_worker_tls_alb[0].id
  referenced_security_group_id = aws_security_group.research_worker_java_identity[0].id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
  description                  = "HTTPS only from dedicated Java backend ECS identity SG"
}

resource "aws_vpc_security_group_egress_rule" "worker_tls_to_target" {
  count                        = local.worker_tls_count
  security_group_id            = aws_security_group.research_worker_tls_alb[0].id
  referenced_security_group_id = aws_security_group.research_worker[0].id
  from_port                    = 8090
  to_port                      = 8090
  ip_protocol                  = "tcp"
  description                  = "Internal TLS edge to isolated Python HTTP target only"
}

resource "aws_vpc_security_group_ingress_rule" "worker_from_tls_alb" {
  count                        = local.worker_tls_count
  security_group_id            = aws_security_group.research_worker[0].id
  referenced_security_group_id = aws_security_group.research_worker_tls_alb[0].id
  from_port                    = 8090
  to_port                      = 8090
  ip_protocol                  = "tcp"
  description                  = "Worker accepts HTTP only from internal TLS edge, not directly from Java"
}

resource "aws_lb" "research_worker_tls" {
  count                      = local.worker_tls_count
  name                       = "aitrading-rsrch-private"
  internal                   = true
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.research_worker_tls_alb[0].id]
  subnets                    = aws_subnet.private[*].id
  drop_invalid_header_fields = true
  idle_timeout               = 30
  enable_http2               = true

  lifecycle {
    precondition {
      condition = (
        var.aws_region == "us-east-1" &&
        data.aws_caller_identity.current.account_id == "816344830716"
      )
      error_message = "V81 internal worker ALB must be in AiTradingSystem 816344830716/us-east-1."
    }
  }
  tags = local.worker_tags
}

resource "aws_lb_target_group" "research_worker_tls" {
  count                = local.worker_tls_count
  name                 = "aitrading-rsrch-8090"
  vpc_id               = aws_vpc.main.id
  target_type          = "ip"
  port                 = 8090
  protocol             = "HTTP"
  deregistration_delay = 10

  health_check {
    enabled             = true
    protocol            = "HTTP"
    path                = "/health"
    port                = "traffic-port"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
  tags = local.worker_tags
}

resource "aws_lb_listener" "research_worker_tls" {
  count             = local.worker_tls_count
  load_balancer_arn = aws_lb.research_worker_tls[0].arn
  port              = 443
  protocol          = "HTTPS"
  certificate_arn   = aws_acm_certificate_validation.research_worker_tls[0].certificate_arn
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"

  # Deny every path unless explicitly allowed below. This prevents future
  # Python routes (admin, metrics, docs, debug) from becoming reachable
  # automatically through the private ALB.
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}

resource "aws_lb_listener_rule" "research_worker_private_api_only" {
  count        = local.worker_tls_count
  listener_arn = aws_lb_listener.research_worker_tls[0].arn
  priority     = 10
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.research_worker_tls[0].arn
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

# A PRIVATE hosted zone with the same exact host name keeps the worker invisible
# to public DNS. The PUBLIC parent hosted zone is used ONLY for ACM validation.
resource "aws_route53_zone" "research_worker_private" {
  count = local.worker_tls_count
  name  = local.worker_tls_fqdn
  vpc {
    vpc_id = aws_vpc.main.id
  }
  tags = local.worker_tags
}

resource "aws_route53_record" "research_worker_private" {
  count   = local.worker_tls_count
  zone_id = aws_route53_zone.research_worker_private[0].zone_id
  name    = local.worker_tls_fqdn
  type    = "A"
  alias {
    name    = aws_lb.research_worker_tls[0].dns_name
    zone_id = aws_lb.research_worker_tls[0].zone_id
    # Zero worker targets is expected. DNS resolution is necessary for
    # certificate preflight but never implies a healthy model worker.
    evaluate_target_health = false
  }
}

output "research_worker_private_https_url" {
  description = "Operator-only, private-VPC HTTPS URL; does not enable Java client, API token, inference, scaling or capital."
  value       = var.research_worker_tls_enabled ? "https://${local.worker_tls_fqdn}" : null
}
