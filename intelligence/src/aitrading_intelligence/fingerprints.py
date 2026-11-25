import hashlib
import json
from typing import Any

from .contracts import EvidenceContractRequest


def canonical_fingerprint(payload: Any) -> str:
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def request_fingerprint(request: EvidenceContractRequest) -> str:
    payload = request.model_dump(mode="json", exclude_none=True)
    return canonical_fingerprint(payload)
