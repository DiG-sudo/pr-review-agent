#!/usr/bin/env bash
set -euo pipefail

repo=DiG-sudo/test
action=${1:-}
number=${2:-1}

if ! command -v gh >/dev/null; then
  echo '需要 GitHub CLI：brew install gh，然后执行 gh auth login' >&2
  exit 1
fi

case "$action" in
  create)
    gh pr create -R "$repo" -B main -H replay-pr-8087 \
      -t 'Replay Cal.com PR #8087: async appStore imports' \
      -b 'Replays the code changes from Cal.com PR #8087 for PR reviewer testing.'
    ;;
  close)
    gh pr close -R "$repo" "$number"
    ;;
  reopen)
    gh pr reopen -R "$repo" "$number"
    ;;
  trigger)
    if [[ $(gh pr view -R "$repo" "$number" --json state --jq .state) == OPEN ]]; then
      gh pr close -R "$repo" "$number"
    fi
    gh pr reopen -R "$repo" "$number"
    ;;
  *)
    echo "用法: $0 {create|close|reopen|trigger} [PR编号，默认1]" >&2
    exit 2
    ;;
esac
