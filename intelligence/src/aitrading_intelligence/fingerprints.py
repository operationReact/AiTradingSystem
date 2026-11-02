import hashlib
import json

from .contracts import EvidenceContractRequest


def request_fingerprint(request: EvidenceContractRequest) -> str:
    payload = request.model_dump(mode="json", exclude_none=True)
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
