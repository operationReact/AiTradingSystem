FROM python:3.11-slim

# Shared research imports use native LightGBM/OpenMP even when inference is
# disabled. The slim Python base omits libgomp.so.1 and fails on import.
RUN apt-get update && \
    apt-get install -y --no-install-recommends libgomp1 && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
RUN pip install --no-cache-dir .

USER 65532:65532
EXPOSE 8090
# Always execute the fail-closed startup policy before starting Uvicorn.
# Terraform PAPER service sets AI_RESEARCH_STRICT_BOOT_REQUIRED=true.
CMD ["sh", "-c", "python -m aitrading_intelligence.research_startup_gate && exec uvicorn aitrading_intelligence.app:app --host 0.0.0.0 --port 8090"]
