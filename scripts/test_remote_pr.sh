#!/usr/bin/env bash
set -euo pipefail

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$GH_CALLS"
if [[ $1 == pr && $2 == view ]]; then printf '%s\n' "$PR_STATE"; fi
EOF
chmod +x "$tmp/gh"

for state in OPEN CLOSED; do
  calls="$tmp/$state"
  PATH="$tmp:$PATH" GH_CALLS="$calls" PR_STATE="$state" \
    bash scripts/remote_pr.sh trigger 1
  if [[ $state == OPEN ]]; then
    expected=$'pr view -R DiG-sudo/test 1 --json state --jq .state\npr close -R DiG-sudo/test 1\npr reopen -R DiG-sudo/test 1'
  else
    expected=$'pr view -R DiG-sudo/test 1 --json state --jq .state\npr reopen -R DiG-sudo/test 1'
  fi
  [[ $(cat "$calls") == "$expected" ]]
done
echo 'remote_pr.sh trigger: OK'
