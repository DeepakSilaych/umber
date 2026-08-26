"""Generic client for the self-hosted LLM gateway. Not insights-specific — the still-unbuilt
POST /v1/classify merchant-classification job described in docs/SYNC.md can reuse this."""

import httpx

from app.config import Settings


def chat_completion(settings: Settings, system_prompt: str, user_content: str, *, timeout: float = 8.0) -> str:
    resp = httpx.post(
        f"{settings.llm_gateway_url}/chat/completions",
        json={
            "model": settings.llm_model,
            "temperature": 0,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_content},
            ],
        },
        headers={"Authorization": f"Bearer {settings.llm_gateway_key}"},
        timeout=timeout,
    )
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"]
