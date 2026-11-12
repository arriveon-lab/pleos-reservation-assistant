#!/usr/bin/env python3
"""Check commit/PR titles and issue-numbered branch names without third-party packages."""
import argparse
from itertools import count
import json
import os
from pathlib import Path
import re
import sys
from urllib.request import Request, urlopen

TYPES = "feat|fix|refactor|docs|test|style|chore|ci|build|perf|revert"
SCOPES = "server|android|api|recommendation|calls|ux|docs|infra|deps|repo"
TITLE = re.compile(rf"(?:{TYPES})\((?:{SCOPES})\)!?: \S.*")
BRANCH = re.compile(rf"(?:{TYPES})/[1-9][0-9]*-[a-z0-9]+(?:-[a-z0-9]+)*")


def check_title(title):
    if not TITLE.fullmatch(title) or len(title) > 72 or title != title.rstrip():
        raise ValueError("제목은 72자 이내 type(scope): 설명 형식이어야 합니다. 예: feat(android): 목적지 조회 추가")


def check_branch(branch):
    if not BRANCH.fullmatch(branch) or len(branch) > 100:
        raise ValueError("작업 브랜치는 type/이슈번호-short-topic 형식이어야 합니다. 예: feat/12-navi-probe")


def check_message(message):
    lines = [line for line in message.splitlines() if not line.startswith("#")]
    while lines and not lines[0].strip():
        lines.pop(0)
    check_title(lines[0] if lines else "")
    if len(lines) > 1 and lines[1].strip():
        raise ValueError("커밋 제목과 본문 사이에 빈 줄을 넣으세요.")


def has_open_sub_issue(repository, issue):
    api_url = os.environ["GITHUB_API_URL"].rstrip("/")
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {os.environ['GH_TOKEN']}",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    for page in count(1):
        url = f"{api_url}/repos/{repository}/issues/{issue}/sub_issues?per_page=100&page={page}"
        with urlopen(Request(url, headers=headers), timeout=10) as response:
            sub_issues = json.load(response)
        if any(sub_issue["state"] == "open" for sub_issue in sub_issues):
            return True
        if len(sub_issues) < 100:
            return False


def check_issue_reference(pr, issue):
    body = pr.get("body") or ""
    if re.search(rf"(?im)^Closes #{issue}\s*$", body):
        return
    if not re.search(rf"(?im)^Refs #{issue}\s*$", body):
        raise ValueError(f"PR 본문에 브랜치 이슈와 일치하는 Closes #{issue} 또는 Refs #{issue}를 별도 줄로 넣으세요.")
    if not has_open_sub_issue(pr["base"]["repo"]["full_name"], issue):
        raise ValueError(f"Refs #{issue}를 사용하려면 열린 하위 이슈를 연결하세요.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--message-file", type=Path)
    parser.add_argument("--title")
    parser.add_argument("--branch")
    parser.add_argument("--event-file", type=Path)
    args = parser.parse_args()
    if not any([args.message_file, args.title is not None, args.branch is not None, args.event_file]):
        parser.error("검사할 메시지/제목/브랜치/GitHub 이벤트 파일을 지정하세요.")
    if args.message_file:
        check_message(args.message_file.read_text(encoding="utf-8"))
    if args.title is not None:
        check_title(args.title)
    if args.branch is not None:
        check_branch(args.branch)
    if args.event_file:
        pr = json.loads(args.event_file.read_text(encoding="utf-8"))["pull_request"]
        check_title(pr["title"])
        check_branch(pr["head"]["ref"])
        if pr["base"]["ref"] != "main":
            raise ValueError("PR 대상 브랜치는 main이어야 합니다.")
        issue = pr["head"]["ref"].split("/", 1)[1].split("-", 1)[0]
        check_issue_reference(pr, issue)
    print("PASS: naming conventions")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
