#!/usr/bin/env bash
# 50-cycle UI/UX hardening loop. Emits AGENT_LOOP_TICK_UIUX per cycle.
# Cycle prompt rotates scope: see NEXT_CYCLE_PROMPT below; agent edits for subsequent ticks.
# Total iterations: ${MAX_ITER} (default 50). Cadence: ${INTERVAL_S}s.
set -u
MAX_ITER=${MAX_ITER:-50}
INTERVAL_S=${INTERVAL_S:-300}
ITER_FILE=/Users/brandon/Downloads/gallery-main/.cursor/loops/.uiux_iter
[ -f "$ITER_FILE" ] || echo 0 > "$ITER_FILE"
N=$(cat "$ITER_FILE")
while [ "$N" -lt "$MAX_ITER" ]; do
  N=$((N+1))
  echo "$N" > "$ITER_FILE"
  sleep "$INTERVAL_S"
  echo "AGENT_LOOP_TICK_UIUX {\"cycle\":$N,\"max\":$MAX_ITER,\"prompt\":\"UI/UX hardening cycle $N/$MAX_ITER. Read .cursor/loops/uiux_state.json for last cycle findings + next target. 1) read prior findings, 2) pick top 1-2 issues not yet fixed, 3) fix with hard-ban compliance (no try/catch, no as any, no raw Color outside theme/), 4) verify: gradlew assembleDebug + testDebugUnitTest + install + screenshot on V23001960, 5) update uiux_state.json with cycle results, 6) re-arm this loop by re-running with same MAX_ITER.\"}"
done
echo "AGENT_LOOP_DONE_UIUX $(date +%s) cycles=$MAX_ITER"
