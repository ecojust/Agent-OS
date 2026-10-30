INCLUDE PERFETTO MODULE android.startup.time_to_display;
SELECT hex(json_object(
 'bounds',json((SELECT json_object('start',start_ts,'end',end_ts) FROM trace_bounds)),
 'markers',json((SELECT json_group_array(json_object('name',s.name,'start',s.ts,'end',s.ts+s.dur,'pid',p.pid))
 FROM slice s JOIN thread_track tr ON s.track_id=tr.id JOIN thread t ON tr.utid=t.utid JOIN process p ON t.upid=p.upid
 WHERE s.name GLOB 'aos.experience.launch.*')),
 'startups',json((SELECT json_group_array(json_object(
 'id',s.startup_id,'start',s.ts,'end',s.ts_end,'type',s.startup_type,'pid',sp.pid,'upid',sp.upid,
 'ttid',d.time_to_initial_display,'frameId',d.ttid_frame_id,'frameUpid',d.upid,
 'uiSliceId',f.do_frame_id,'drawSliceId',f.draw_frame_id,
 'uiStart',ui.ts,'uiDur',ui.dur,'uiPid',up.pid,'uiTid',ut.tid,
 'rtStart',rt.ts,'rtDur',rt.dur,'rtEnd',rt.ts+rt.dur,'rtTid',rtt.tid,'rtPid',rp.pid,'rtThread',rtt.name))
 FROM android_startups s
 LEFT JOIN android_startup_processes sp USING(startup_id)
 LEFT JOIN android_startup_time_to_display d USING(startup_id)
 LEFT JOIN android_frames f ON f.frame_id=d.ttid_frame_id AND f.upid=d.upid
 LEFT JOIN slice ui ON ui.id=f.do_frame_id LEFT JOIN slice rt ON rt.id=f.draw_frame_id
 LEFT JOIN thread ut ON ut.utid=f.ui_thread_utid LEFT JOIN process up ON up.upid=ut.upid
 LEFT JOIN thread rtt ON rtt.utid=f.render_thread_utid LEFT JOIN process rp ON rp.upid=rtt.upid
 WHERE s.package='dev.agentos.fixture')),
 'stats',json((SELECT json_group_array(json_object('name',name,'index',idx,'severity',severity,'source',source,'value',value))
 FROM stats WHERE value>0 AND (severity IN ('error','data_loss') OR name LIKE '%bytes%' OR name LIKE '%packets%' OR name LIKE '%overrun%' OR name LIKE '%overwrit%' OR name LIKE '%drop%' OR name LIKE '%loss%')))
)) AS audit;
