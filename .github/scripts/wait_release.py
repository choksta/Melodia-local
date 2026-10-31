#!/usr/bin/env python3
"""轮询等待某个 tag 的 Release 构建结果，完成即返回。

用法:
    python3 wait_release.py v2.1.0-beta.1-local36 [超时秒数]

为什么不 sleep 硬等：构建通常 6~8 分钟，但会波动。盲等固定时长要么白等，
要么还得再补等一轮。轮询每 20 秒问一次，构建一结束就继续。

退出码：0 = 成功，1 = 失败，2 = 超时。
"""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

REPO = "choksta/Melodia-local"
WORKFLOW = "release.yml"
POLL_INTERVAL = 20


def token() -> str:
    out = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True,
        text=True,
    ).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line[len("password="):].strip()
    raise SystemExit("拿不到 GitHub 凭据")


def api(path: str, auth: str):
    req = urllib.request.Request(
        f"https://api.github.com{path}",
        headers={
            "Authorization": f"Bearer {auth}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "melodia-ci",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return {"error": e.code, "body": e.read().decode()[:200]}


def find_run(tag: str, auth: str):
    data = api(f"/repos/{REPO}/actions/workflows/{WORKFLOW}/runs?per_page=20", auth)
    for run in data.get("workflow_runs", []):
        if run["head_branch"] == tag:
            return run
    return None


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    tag = sys.argv[1]
    timeout = int(sys.argv[2]) if len(sys.argv) > 2 else 900

    auth = token()
    began = time.time()
    last_state = None

    while time.time() - began < timeout:
        run = find_run(tag, auth)
        if run is not None:
            state = (run["status"], run["conclusion"])
            if state != last_state:
                elapsed = int(time.time() - began)
                print(f"[{elapsed:>3}s] {run['status']} / {run['conclusion'] or '进行中'}", flush=True)
                last_state = state

            if run["status"] == "completed":
                ok = run["conclusion"] == "success"
                print(f"结论：{run['conclusion']}（用时 {int(time.time() - began)}s）")
                if ok:
                    rel = api(f"/repos/{REPO}/releases/tags/{tag}", auth)
                    if "assets" in rel and rel["assets"]:
                        size = rel["assets"][0]["size"] / 1048576
                        print(f"Release：{rel['html_url']}")
                        print(f"APK：{rel['assets'][0]['name']}（{size:.2f} MB）")
                return 0 if ok else 1

        time.sleep(POLL_INTERVAL)

    print(f"超时：{timeout}s 内未完成")
    return 2


if __name__ == "__main__":
    sys.exit(main())
