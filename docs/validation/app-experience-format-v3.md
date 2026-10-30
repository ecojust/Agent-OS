# 目标应用诊断 V3 数据格式与就绪观察合同

本文定义 V3 数据格式和校验条件；实际设备结果与完成状态以[诊断记录](performance-app-experience.md)为准。

V1/V2 archived files keep their original contracts. Root/launch/resource shard schemaVersion=3 and Probe identity schemaVersion=3, with no mixing. All v2 fields/semantics remain unless explicitly changed below. Resource trial quality, sample counts/order, 32KiB launch cap, CPU/PSS, original one am start, original trace marker/afterLaunch/first-draw endpoints and external official TTID stay unchanged. No invalidation, animation override, action/launch replay or new TTID selection.

## Probe3 additions (all status/begin/end scalar snapshots)

EXACT long >=0: `focusGainCount`, `focusLossCount`, `firstFocusGainNanoTime`, `lastFocusGainNanoTime`, `lastFocusLossNanoTime`. Reset on new Activity identity. `focused(instance,value)` retains existing instance guard and counts only true boolean transitions (initial/duplicate false is not a loss). First real gain sets firstFocusGainNanoTime once; each gain/loss sets corresponding last time using System.nanoTime. Thus gain0 => first/lastgain0; loss0 => lastloss0; focused => gain=loss+1, notfocused => gain=loss. Existing windowFocused remains authoritative bool. No lifecycle or draw callbacks synthesize focus transitions.

## Root3 conditions additions EXACT

`a11yReadinessMaxObservations=16`, `a11yReadinessTimeoutMillis=3000`, `a11yReadinessIntervalMillis=200`, `a11yReadinessPolicy="post_marker_read_only_identity_lifecycle_focus_guard"`.

## Launch3 additions EXACT

- `readinessStartedNanos`, `readinessEndedNanos`: elapsedRealtimeNanos, -1 until known; start >= launchEndedElapsedNanos. On attempted observation/failure always retain end in finally.
- `readinessVerified`: bool, initially false; true only complete stable ready observation and final readiness end strictly before the 3000ms deadline.
- `readinessFailureReason`: string initially empty; enum empty / `provider_identity_changed` / `provider_lifecycle_changed` / `provider_focus_changed` / `provider_not_resumed_focused` / `agent_state_changed` / `a11y_timeout` / `observation_failed`.
- `readinessObservations`: bounded array <=16. Entries are appended before reads, so an interrupted/failed entry retains known fields and nulls for fields not read. Exact entry keys: `index`, `startedNanos`, `endedNanos`, `providerBefore`, `providerAfter`, `agentBefore`, `agentAfter`, `a11y`, `reason`. index contiguous from0; elapsed timestamps, ended=-1 only not yet known. reason same terminal enum as above, plus empty for stable observations (whether a11y ready or pending). Only final entry may have nonempty reason; no observations after ready or terminal guard error.

Compact providerBefore/After are null or exact 19-element JSON arrays, fixed field order:
`[pid, processInstance, activityInstance, createCount, startCount, resumeCount, pauseCount, stopCount, destroyCount, newIntentCount, focusGainCount, focusLossCount, firstFocusGainNanoTime, lastFocusGainNanoTime, lastFocusLossNanoTime, resumed, stopped, windowFocused, checkedNanoTime]`.
PID positive int; UUID strings; counts/times nonnegative longs; positions15/16/17 bool; checkedNanoTime is driver System.nanoTime immediately after provider status returns. All provider identities anchored to immutable afterLaunch. Arrays intentionally avoid repeating verbose keys twice per observation within 32KiB.

Compact agentBefore/After are null or exact 5-element arrays:
`[servicePresent, serviceConnected, controllerRunning, controlAvailable, checkedNanos]`.
Four bools then elapsedRealtimeNanos. Reads on instrumentation main thread; no extra hidden a11y read. Arm A requires false,false,false,false; B requires true,true,false,false throughout.

`a11y` is null or exact object:
`{windowCount, focusedWindowCount, focusedWindowId, focusedWindowType, rootPresent, packageName, editorMatchCount, editorVisible, ready, reason}`.
windowCount=-1 only getWindows null, otherwise total list size. focusedWindowCount counts focused windows on default display (0 if unavailable). focusedWindowId/type=-1 unless count exactly1. rootPresent false until unique focused TYPE_APPLICATION root is nonnull. packageName empty until root package observed; otherwise real string <=256. editorMatchCount=-1 until root is Fixture and search executes, otherwise actual nonnegative match count. editorVisible true only exactly1 match and that node isVisibleToUser. ready exactly unique focused default-display application window + nonnull Fixture root + exactly1 visible editor. Reason enum, checked in order: `windows_unavailable`, `focused_window_count`, `focused_window_not_application`, `root_missing`, `package_mismatch`, `editor_match_count`, `editor_not_visible`, `ready`. No full windows/nodes list retained; no UI writes.

## Guard, sequencing, failure and time semantics

Anchor is unchanged afterLaunch plus cold/hot preparation:
- cold afterLaunch: gain1,loss0,firstGain=lastGain>=launchStartedNanoTime,lastLoss0. This rejects real loss even if it occurred before the observed first draw and later recovered.
- hot anchor beforeLaunch is the already stopped HOME snapshot: windowFocused=false (required both by the HOME await and again after the fixed1000ms settle); afterLaunch gain=beforeGain+1,loss=beforeLoss,firstGain unchanged, lastLoss unchanged, lastGain>=launchStartedNanoTime. Before/after retain same PID/processUUID/activityUUID. A real loss during launch cannot be accepted after recovery.
- Each compact provider before/after must equal afterLaunch PID/process/activity; create/start/resume/pause/stop/destroy/newIntent counts; all five focus counters/times. It must be resumed&&!stopped&&windowFocused. Compare before snapshot to anchor before a11y; if invalid, save terminal observation and fail immediately (a11y/after remain null). Capture providerBefore, agentBefore, guard; read a11y once; capture agentAfter then providerAfter; guard both sides. Any identity/lifecycle/focus or Agent-state mismatch is terminal; no waiting for recovery. Priority: identity, lifecycle, focus counters/anchor, foreground bools, agent state.
- Full observation pending/ready is recorded before any return/assertion. Stable but a11y false permits only another read-only observation. Set stateAfter from last captured compact Agent state + this observation's a11y.ready (false if not read); checkedNanos is Agent timestamp. It represents final observed readiness state; earlier false remains in observations.
- Up to16 starts, no new start at/after readinessStart+3000ms; between pending completion and next start sleep200ms, capped at remaining deadline. A platform synchronous read may finish beyond deadline; retain its raw completed observation, set reason a11y_timeout, fail (never pass beyond 3000ms). No asynchronous timeout workers/replays. Sequence2300s deadline is also enforced.
- Passed/unavailable launch with completed original lifecycle requires readinessVerified=true, last observation ready, all observations complete and guarded, readinessFailureReason empty; a prior original unavailable natural-frame reason remains unavailable and is not changed into passed. A guard/timeout/read exception makes launch failed and root stops exactprefix. If original launch fails before readiness, new fields remain -1/false/empty/[]; preserve known original endpoints.
- For terminal guard failure, readinessFailureReason equals final observation.reason. For exception, mark observation_failed and retain partial entry then rethrow. For timeout between observations, reason a11y_timeout is assigned to final pending observation (no invented extra read); retain ended timestamp from its actual read, readinessEnded marks timeout.

Host validation must preserve partial failed entries and validate only known fields, while never accepting passed rows with missing snapshots, guard failures, omitted initial false, beyond-deadline success, reordered observations, or final nonready. Raw observations do not claim stable focus between arbitrary outside samples; monotonic target focus counters prevent observed launch/readiness losses being hidden by later recovery.
