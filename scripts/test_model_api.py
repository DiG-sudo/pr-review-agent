#!/usr/bin/env python3
"""One OpenAI-compatible request; AI_BASE_URL includes the provider's /v1 prefix."""
import json
import os
import sys
import time
import urllib.error
import urllib.request


def main():
    names = ("AI_API_KEY", "AI_BASE_URL", "AI_MODEL")
    missing = [name for name in names if not os.environ.get(name)]
    if missing:
        print("缺少环境变量：" + ", ".join(missing), file=sys.stderr)
        return 1

    key, base, model = (os.environ[name] for name in names)
    url = base.rstrip("/") + "/chat/completions"
    request = urllib.request.Request(url, method="POST", headers={
        "Authorization": "Bearer " + key,
        "Content-Type": "application/json",
    }, data=json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": "仅回复 OK。"}],
        "max_tokens": 128,
        "stream": False,
    }).encode("utf-8"))
    print("请求地址：" + url.replace(key, "***"), flush=True)
    print("模型：" + model, flush=True)
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            status = response.status
            payload = json.load(response)
        print(f"HTTP {status}，耗时 {time.monotonic() - started:.2f}s")
        choice = payload["choices"][0]
        print("模型回复：" + str(choice["message"].get("content") or "（空回复）"))
        print("结束原因：" + str(choice.get("finish_reason")))
        print("用量：" + json.dumps(payload.get("usage", {}), ensure_ascii=False))
        return 0
    except urllib.error.HTTPError as error:
        body = error.read(2048).decode("utf-8", errors="replace").replace(key, "***")
        print(f"HTTP {error.code}，耗时 {time.monotonic() - started:.2f}s\n{body}", file=sys.stderr)
    except (urllib.error.URLError, TimeoutError, OSError, ValueError, KeyError, IndexError) as error:
        print(f"调用失败：{type(error).__name__}: {str(error).replace(key, '***')}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
