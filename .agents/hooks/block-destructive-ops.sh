#!/usr/bin/env bash
# Block destructive shell commands under Antigravity (PreToolUse on run_command).
#
# Antigravity hook contract: receive a JSON payload on stdin, write a JSON
# object to stdout with a "decision" of "allow" or "deny" (with an optional
# "reason"). This is the Antigravity port of the Gemini CLI hook described in
# https://medium.com/google-cloud/migrating-to-antigravity-cli-a841c6964f37
# (BeforeTool -> PreToolUse, run_shell_command -> run_command,
#  .tool_input.command -> .toolCall.args.CommandLine, exit-code -> JSON decision).
#
# Catches operations that can cause irreversible damage:
#   rm -rf / or ~ · git push --force/-f or +refspec · git reset --hard
#   git branch -D · chmod 777 · curl|bash / wget|sh (pipe-to-shell)
#
# Bypass-resistant by design: the shell must parse the literal command word
# ('git', 'rm', '--force') to run it, so an agent can't obfuscate the tokens
# without also breaking the destructive command itself.

input=$(cat)

# Extract the command; fall back to sed if jq is missing so the gate never
# silently fails open.
cmd=""
if command -v jq >/dev/null 2>&1; then
  cmd=$(printf '%s' "$input" | jq -r '.toolCall.args.CommandLine // empty' 2>/dev/null)
fi
[ -z "$cmd" ] && cmd=$(printf '%s' "$input" | sed -n 's/.*"CommandLine"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')

# Match the extracted command AND the raw payload (backstop).
haystack="$cmd
$input"

patterns=(
  'rm[[:space:]]+-r?f?[[:space:]]+/[[:space:]]*$'             # rm -rf /
  'rm[[:space:]]+-r?f?[[:space:]]+~'                          # rm -rf ~
  'git[[:space:]]+push([[:space:]].*)?--force'               # git push --force
  'git[[:space:]]+push([[:space:]].*)?[[:space:]]-f([[:space:]]|$)'  # git push -f
  'git[[:space:]]+push[[:space:]].*[[:space:]]\+[A-Za-z0-9_./-]+'    # git push origin +branch (force refspec)
  'git[[:space:]]+reset[[:space:]]+--hard'                    # git reset --hard
  'git[[:space:]]+branch[[:space:]]+-D'                       # git branch -D
  'chmod[[:space:]]+-?R?[[:space:]]*777'                      # chmod 777
  'curl[^|]+\|[[:space:]]*(bash|sh)([[:space:]]|$)'           # curl | bash
  'wget[^|]+\|[[:space:]]*(bash|sh)([[:space:]]|$)'           # wget | bash
)

for p in "${patterns[@]}"; do
  if printf '%s' "$haystack" | grep -qE "$p"; then
    printf '{"decision":"deny","reason":"Destructive operation blocked by safety-gate policy. If this was intentional, run it yourself outside the agent loop — agents should not initiate operations that cannot be undone."}\n'
    exit 0
  fi
done

printf '{"decision":"allow"}\n'
exit 0
