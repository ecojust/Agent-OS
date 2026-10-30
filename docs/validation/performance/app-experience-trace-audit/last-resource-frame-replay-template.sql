-- Replace the three integer placeholders from the corresponding audit JSON row.
INCLUDE PERFETTO MODULE android.frames.timeline;
SELECT f.frame_id,f.upid,p.pid,ui.id AS ui_slice_id,ui.ts AS ui_start_ns,ui.ts+ui.dur AS ui_end_ns,rt.id AS render_slice_id,rt.ts AS render_start_ns,rt.ts+rt.dur AS render_end_ns
FROM android_frames f JOIN process p ON p.upid=f.upid
JOIN slice ui ON ui.id=f.do_frame_id JOIN slice rt ON rt.id=f.draw_frame_id
WHERE p.pid=TARGET_PID AND ui.ts<=DRAW_BOOT_MIN AND ui.ts+ui.dur>DRAW_BOOT_MAX;
