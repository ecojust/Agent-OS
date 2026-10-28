package dev.agentos.core;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class AgentRunnerTest {
    private static final String KEY = "private-test-key-never-log";
    private static final String IMAGE = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==";
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static JsonObject observation(String revision) {
        JsonObject result = json("{\"ok\":true,\"data\":{\"packageName\":\"demo.app\",\"nodes\":[]}}");
        result.getAsJsonObject("data").addProperty("revision", revision);
        return result;
    }
    private static JsonObject ok() { return json("{\"ok\":true}"); }
    private static String tool(String id, String name, String args) {
        JsonObject function = new JsonObject(); function.addProperty("name", name); function.addProperty("arguments", args);
        JsonObject call = new JsonObject(); call.addProperty("id", id); call.addProperty("type", "function"); call.add("function", function);
        return call.toString();
    }
    private static String replyTools(String... calls) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" + String.join(",", calls) + "]}}]}";
    }
    private static String replyText(String text) {
        JsonObject message = new JsonObject(); message.addProperty("role", "assistant"); message.addProperty("content", text);
        JsonObject choice = new JsonObject(); choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject result = new JsonObject(); result.add("choices", choices); return result.toString();
    }

    private static final class Events implements AgentRunner.Listener {
        final List<String> log = new CopyOnWriteArrayList<>();
        final List<TaskActionReceipts> receipts = new CopyOnWriteArrayList<>();
        final AtomicInteger finishes = new AtomicInteger();
        volatile String summary;
        volatile boolean success;
        volatile TaskResult result;
        public void onStatus(String message) { log.add(message); }
        public void onStep(String message) { log.add(message); }
        public void onActionReceipts(TaskActionReceipts snapshot) { receipts.add(snapshot); }
        public void onFinished(String message, boolean success) { summary = message; this.success = success; log.add(message); finishes.incrementAndGet(); }
        public void onResult(TaskResult result) { this.result=result; AgentRunner.Listener.super.onResult(result); }
    }
    private static final class Request {
        final String path, authorization, body;
        Request(String path, String authorization, String body) { this.path=path; this.authorization=authorization; this.body=body; }
        JsonObject json() { return AgentRunnerTest.json(body); }
    }
    private static final class Reply {
        final int status; final String body; final String location;
        Reply(String body) { this(200, body, null); }
        Reply(int status, String body, String location) { this.status=status; this.body=body; this.location=location; }
    }
    @FunctionalInterface private interface Responder { Reply respond(int index, Request request) throws Exception; }
    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Server(Responder responder) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    Request request = new Request(exchange.getRequestURI().toString(), exchange.getRequestHeaders().getFirst("Authorization"), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    int index = requests.size(); requests.add(request);
                    Reply response = responder.respond(index, request);
                    byte[] bytes=response.body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    if(response.location!=null) exchange.getResponseHeaders().add("Location", response.location);
                    exchange.sendResponseHeaders(response.status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (IOException ignored) {
                    // A cancelled HTTP request may close its socket before the fixture responds.
                } catch (Throwable error) { failure.compareAndSet(null,error); }
                finally { exchange.close(); }
            });
            server.start();
        }
        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; }
        void assertHealthy() { assertNull("mock server callback failed: " + failure.get(), failure.get()); }
        public void close() { server.stop(0); executor.shutdownNow(); }
    }
    private static AgentRunner runner(Server server, ToolHandler handler, Events events) {
        return new AgentRunner(new ModelSettings(server.endpoint(), KEY, "test-model"), handler, events);
    }
    private static List<JsonObject> toolMessages(JsonObject request) {
        List<JsonObject> messages = new ArrayList<>();
        for(JsonElement entry:request.getAsJsonArray("messages")) {
            JsonObject message=entry.getAsJsonObject();
            if("tool".equals(message.get("role").getAsString())) messages.add(message);
        }
        return messages;
    }

    @Test public void bootstrapFromUnscopedControllerOnlyLaunchesAnAuthorizedApplication() throws Exception {
        Events events = new Events(); List<String> actions = new ArrayList<>(); AtomicInteger observations = new AtomicInteger();
        try (Server server = new Server((index, request) -> new Reply(index == 0
                ? replyTools(tool("launch", "launch_app", "{\"packageName\":\"demo.app\"}")) : replyText("目标应用已打开。")))) {
            runner(server, (name, args, control) -> {
                actions.add(name);
                if (name.equals("observe") && observations.incrementAndGet() == 1) {
                    return json("{\"ok\":true,\"data\":{\"revision\":\"r0\",\"packageName\":\"dev.agentos.app\",\"scoped\":false,\"nodes\":[]}}");
                }
                return name.equals("observe") ? observation("r1") : ok();
            }, events).run("打开允许的应用", new RunControl());
            assertEquals(Arrays.asList("observe", "launch_app", "observe"), actions);
            assertEquals(TaskResult.Kind.MODEL_REPORT, events.result.kind);
            assertEquals(2, server.requests.size());
        }
    }

    @Test public void actualHttpPlanPublishesLocalActionReceiptsWithoutPromotingItsFinalReport() throws Exception {
        Events events = new Events(); RunControl control = new RunControl(); AtomicInteger writes = new AtomicInteger();
        try (Server server = new Server((index, request) -> new Reply(index == 0
                ? replyTools(tool("private-model-call", "launch_app", "{\"packageName\":\"demo.app\"}"))
                : replyText("模型声称任务已完成。")))) {
            runner(server, (name, args, active) -> {
                if ("observe".equals(name)) return observation("r1");
                writes.incrementAndGet();
                try (RunControl.DispatchTicket ignored = active.beginDispatch(active.getExecutionEpoch())) {
                    return json("{\"ok\":true,\"dispatchSubmitted\":true,\"dispatchAccepted\":true,\"verification\":\"UNVERIFIED\"}");
                }
            }, events).run("synthetic-private-task", control);
            assertEquals(2, server.requests.size()); assertEquals(1, writes.get());
            assertEquals(2, events.receipts.size());
            TaskActionReceipts started = events.receipts.get(0), completed = events.receipts.get(1);
            assertEquals(TaskActionReceipts.Stage.WAITING_RESULT, started.entries.get(0).stage);
            TaskActionReceipts.Entry receipt = completed.entries.get(0);
            assertEquals(control.getTaskId(), completed.taskId);
            assertEquals(TaskActionReceipts.Action.LAUNCH_APP, receipt.action);
            assertEquals(TaskActionReceipts.Fact.YES, receipt.accepted);
            assertEquals(TaskActionReceipts.Verification.UNVERIFIED, receipt.verification);
            assertEquals(TaskResult.Kind.MODEL_REPORT, events.result.kind); assertFalse(events.result.verified);
            assertFalse(new Gson().toJson(completed).contains("private"));
            assertEquals(1, events.finishes.get()); server.assertHealthy();
        }
    }

    @Test public void pauseAfterDispatchAttemptEndsWithUnknownOutcomeWithoutReplayingAction() throws Exception {
        Events events = new Events(); RunControl control = new RunControl(); List<String> actions = new ArrayList<>();
        try (Server server = new Server((index, request) -> new Reply(replyTools(tool("write", "click", "{\"nodeId\":\"n1\",\"expectedRevision\":\"r1\"}"))))) {
            runner(server, (name, args, active) -> {
                actions.add(name);
                if (name.equals("observe")) return observation("r1");
                long epoch = active.getExecutionEpoch();
                active.markDispatchAttempt(epoch); active.pause(); active.checkExecutionEpoch(epoch);
                throw new AssertionError("old epoch must not continue");
            }, events).run("执行一次点击", control);
            assertEquals(Arrays.asList("observe", "click"), actions);
            assertEquals("OUTCOME_UNKNOWN", events.result.code);
            assertEquals(TaskResult.Kind.FAILED, events.result.kind);
            assertEquals(1, server.requests.size()); assertEquals(1, events.finishes.get());
        } finally { control.cancel(); }
    }

    @Test public void observesActsAndVerifiesUsingActualHttpToolConversation() throws Exception {
        List<String> actions=new ArrayList<>(); Events events=new Events(); AtomicInteger observations=new AtomicInteger();
        try(Server server=new Server((index, request) -> new Reply(index==0
                ? replyTools(tool("c1", "click", "{\"nodeId\":\"n1\",\"expectedRevision\":\"r1\"}"))
                : replyText("已观察到目标页面。")))) {
            runner(server,(name,args,control)->{ actions.add(name); return name.equals("observe") ? observation("r"+observations.incrementAndGet()) : ok(); },events).run("打开目标页面",new RunControl());
            assertEquals(Arrays.asList("observe","click","observe"),actions);
            assertEquals(2,server.requests.size());
            assertEquals(1,events.finishes.get()); assertFalse("model report is not a verified business result",events.success);
            assertEquals(TaskResult.Kind.MODEL_REPORT,events.result.kind);
            for(Request request:server.requests) {
                assertEquals("/v1/chat/completions",request.path);
                assertEquals("Bearer "+KEY,request.authorization);
                assertFalse(request.body.contains(KEY));
                assertEquals("test-model",request.json().get("model").getAsString());
            }
            List<JsonObject> results=toolMessages(server.requests.get(1).json());
            JsonObject click=results.stream().filter(message->"c1".equals(message.get("tool_call_id").getAsString())).findFirst().orElseThrow();
            assertTrue(json(click.get("content").getAsString()).get("ok").getAsBoolean());
            assertTrue(server.requests.get(1).body.contains("r2"));
            assertFalse(String.join("\n",events.log).contains(KEY));
            server.assertHealthy();
        }
    }

    @Test public void invalidToolsReturnErrorsWithoutCallingDevice() throws Exception {
        Events events=new Events(); AtomicInteger executions=new AtomicInteger();
        try(Server server=new Server((index,request)->new Reply(index==0 ? replyTools(
                tool("a","run_shell","{}"),
                tool("b","click","{\"nodeId\":\"n1\"}"),
                tool("c","tap","{\"x\":\"12\",\"y\":20,\"expectedRevision\":\"r1\"}"),
                tool("d","observe","{broken")) : replyText("请求需要修正。")))) {
            runner(server,(name,args,control)->{executions.incrementAndGet();return observation("r1");},events).run("测试无效操作",new RunControl());
            assertEquals(1,executions.get()); assertEquals(1,events.finishes.get());
            assertEquals(2,server.requests.size());
            List<JsonObject> results=toolMessages(server.requests.get(1).json());
            assertEquals(4,results.size());
            for(JsonObject result:results) assertFalse(json(result.get("content").getAsString()).get("ok").getAsBoolean());
        }
    }

    @Test public void aMutationInvalidatesObservationBeforeTheNextMutation() throws Exception {
        Events events=new Events(); List<String> actions=new ArrayList<>();AtomicInteger observations=new AtomicInteger();
        try(Server server=new Server((index,request)->new Reply(index==0 ? replyTools(
                tool("first","click","{\"nodeId\":\"n1\",\"expectedRevision\":\"r1\"}"),
                tool("stale","click","{\"nodeId\":\"n2\",\"expectedRevision\":\"r1\"}"))
                : replyText("需要重新检查页面。")))) {
            runner(server,(name,args,control)->{actions.add(name);return name.equals("observe")?observation("r"+observations.incrementAndGet()):ok();},events).run("测试顺序",new RunControl());
            assertEquals(Arrays.asList("observe","click","observe"),actions);
            List<JsonObject> results=toolMessages(server.requests.get(1).json());
            JsonObject stale=results.stream().filter(message->"stale".equals(message.get("tool_call_id").getAsString())).findFirst().orElseThrow();
            assertFalse(json(stale.get("content").getAsString()).get("ok").getAsBoolean());
        }
    }

    @Test public void nodeLookupCannotReuseTheSameBatchAfterAWriteEvenWhenTheRevisionIsUnchanged() throws Exception {
        Events events = new Events(); List<String> actions = new ArrayList<>();
        String point = "{\"x\":120,\"y\":160,\"action\":\"click\",\"coordinateSpace\":\"screen_pixels\",\"expectedRevision\":\"r1\"}";
        try (Server server = new Server((index, request) -> new Reply(index == 0
                ? replyTools(tool("write", "click", "{\"nodeId\":\"n1\",\"expectedRevision\":\"r1\"}"),
                        tool("lookup", "resolve_node_at", point)) : replyText("重新规划")))) {
            runner(server, (name, args, control) -> {
                actions.add(name);
                // Some accepted actions do not change the structural revision.
                return name.equals("observe") ? observation("r1") : ok();
            }, events).run("点击后重新定位", new RunControl());
            assertEquals(Arrays.asList("observe", "click", "observe"), actions);
            JsonObject lookup = toolMessages(server.requests.get(1).json()).stream()
                    .filter(message -> "lookup".equals(message.get("tool_call_id").getAsString())).findFirst().orElseThrow();
            JsonObject result = json(lookup.get("content").getAsString());
            assertEquals("STALE_OBSERVATION", result.get("code").getAsString());
            assertFalse(result.get("dispatchSubmitted").getAsBoolean());
            server.assertHealthy();
        }
    }

    @Test public void nodeLookupCannotFollowACaptureInTheSameModelPlan() throws Exception {
        Events events = new Events(); List<String> actions = new ArrayList<>();
        String point = "{\"x\":120,\"y\":160,\"action\":\"click\",\"coordinateSpace\":\"screen_pixels\",\"expectedRevision\":\"r1\"}";
        try (Server server = new Server((index, request) -> new Reply(index == 0
                ? replyTools(tool("image", "take_screenshot", "{\"expectedRevision\":\"r1\"}"),
                        tool("lookup", "resolve_node_at", point)) : replyText("依据图像重新规划")))) {
            ToolHandler tools = new ToolHandler() {
                public JsonObject execute(String name, JsonObject args, RunControl control) {
                    actions.add(name);
                    if (name.equals("observe")) return observation("r1");
                    assertEquals("take_screenshot", name);
                    JsonObject result = ok();
                    result.add("data", json("{\"revision\":\"r1\",\"width\":1,\"height\":1}"));
                    result.addProperty("imageDataUrl", IMAGE); return result;
                }
                public Set<String> availableTools() {
                    Set<String> names = new HashSet<>(ToolHandler.super.availableTools());
                    names.add("take_screenshot"); return names;
                }
            };
            runner(server, tools, events).run("观察后定位", new RunControl());
            assertEquals(Arrays.asList("observe", "take_screenshot"), actions);
            assertTrue(server.requests.get(1).body.contains("image_url"));
            JsonObject lookup = toolMessages(server.requests.get(1).json()).stream()
                    .filter(message -> "lookup".equals(message.get("tool_call_id").getAsString())).findFirst().orElseThrow();
            assertEquals("STALE_OBSERVATION", json(lookup.get("content").getAsString()).get("code").getAsString());
            server.assertHealthy();
        }
    }

    @Test public void aNodeCandidateCannotReplaceTheLatestObservationRevision() throws Exception {
        Events events = new Events(); List<String> actions = new ArrayList<>();
        String point = "{\"x\":120,\"y\":160,\"action\":\"click\",\"coordinateSpace\":\"screen_pixels\",\"expectedRevision\":\"r1\"}";
        try (Server server = new Server((index, request) -> new Reply(index == 0
                ? replyTools(tool("lookup", "resolve_node_at", point)) : index == 1
                ? replyTools(tool("write", "click", "{\"nodeId\":\"n1\",\"expectedRevision\":\"r2\"}")) : replyText("请重新观察")))) {
            runner(server, (name, args, control) -> {
                actions.add(name);
                if (name.equals("observe")) return observation("r1");
                assertEquals("resolve_node_at", name);
                // A faulty read-only result cannot mint an authoritative observation.
                return json("{\"ok\":true,\"data\":{\"nodeId\":\"n1\",\"revision\":\"r2\"}}");
            }, events).run("定位并确认", new RunControl());
            assertEquals(Arrays.asList("observe", "resolve_node_at"), actions);
            JsonObject write = toolMessages(server.requests.get(2).json()).stream()
                    .filter(message -> "write".equals(message.get("tool_call_id").getAsString())).findFirst().orElseThrow();
            assertEquals("STALE_OBSERVATION", json(write.get("content").getAsString()).get("code").getAsString());
            server.assertHealthy();
        }
    }

    @Test public void screenshotIsForwardedAsImageAndNeverLoggedOrRepeatedAsToolText() throws Exception {
        Events events=new Events();
        try(Server server=new Server((index,request)->new Reply(index==0
                ? replyTools(tool("s","take_screenshot","{\"expectedRevision\":\"r1\"}"))
                : replyText("已查看画面。")))) {
            ToolHandler tools=new ToolHandler() {
                public JsonObject execute(String name,JsonObject args,RunControl control) {
                    if(name.equals("observe"))return observation("r1");
                    JsonObject result=ok();result.add("data",json("{\"revision\":\"r1\",\"width\":1,\"height\":1}"));result.addProperty("imageDataUrl",IMAGE);return result;
                }
                public Set<String> availableTools() {
                    Set<String> available=new HashSet<>(ToolHandler.super.availableTools());available.add("take_screenshot");return available;
                }
            };
            runner(server,tools,events).run("查看当前画面",new RunControl());
            JsonObject request=server.requests.get(1).json();
            assertTrue(request.toString().contains("image_url")); assertTrue(request.toString().contains(IMAGE));
            for(JsonObject result:toolMessages(request)) assertFalse(result.get("content").getAsString().contains(IMAGE));
            assertFalse(String.join("\n",events.log).contains(IMAGE));
        }
    }

    @Test public void serverErrorsAreReportedWithoutLeakingResponseOrCredentials() throws Exception {
        for(int status:new int[]{401,500}) {
            Events events=new Events();
            try(Server server=new Server((index,request)->new Reply(status,"{\"error\":\""+KEY+" raw-private-response\"}",null))) {
                runner(server,(name,args,control)->{assertEquals("observe",name);return observation("r1");},events).run("测试错误",new RunControl());
                assertEquals(1,events.finishes.get());assertFalse(events.success);
                String log=String.join("\n",events.log);
                assertFalse(log.contains(KEY));assertFalse(log.contains("raw-private-response"));
            }
        }
    }

    @Test public void redirectsDoNotForwardAuthorizationToAnotherEndpoint() throws Exception {
        Events events=new Events();
        try(Server server=new Server((index,request)->index==0?new Reply(302,"redirect","/unexpected"):new Reply(replyText("unexpected")))) {
            runner(server,(name,args,control)->observation("r1"),events).run("不要重定向",new RunControl());
            assertEquals(1,server.requests.size()); assertFalse(events.success); assertEquals(1,events.finishes.get());
        }
    }

    @Test public void malformedAndOversizedResponsesFailWithoutDeviceActions() throws Exception {
        for(String response:new String[]{"not-json", "{\"choices\":[]}", "x".repeat(8*1024*1024)}) {
            Events events=new Events();AtomicInteger executed=new AtomicInteger();
            try(Server server=new Server((index,request)->new Reply(response))) {
                runner(server,(name,args,control)->{if(!name.equals("observe"))executed.incrementAndGet();return observation("r1");},events).run("检查响应",new RunControl());
                assertFalse(events.success);assertEquals(1,events.finishes.get());assertEquals(0,executed.get());
            }
        }
    }

    @Test public void duplicateToolRequestReusesResultAndChangedParametersAreRejected() throws Exception {
        Events events=new Events();List<String> actions=new ArrayList<>();AtomicInteger observations=new AtomicInteger();
        try(Server server=new Server((index,request)->new Reply(switch(index) {
            case 0,1 -> replyTools(tool("same","click","{\"nodeId\":\"n1\",\"expectedRevision\":\"r1\"}"));
            case 2 -> replyTools(tool("same","click","{\"nodeId\":\"n2\",\"expectedRevision\":\"r2\"}"));
            default -> replyText("重复调用检查完成。");
        }))) {
            runner(server,(name,args,control)->{actions.add(name);return name.equals("observe")?observation("r"+observations.incrementAndGet()):ok();},events).run("检查重复调用",new RunControl());
            assertEquals(1,Collections.frequency(actions,"click"));assertEquals(1,events.finishes.get());
            assertEquals(4,server.requests.size());
            List<JsonObject> results=toolMessages(server.requests.get(3).json());
            JsonObject changed=results.get(results.size()-1);
            assertFalse(json(changed.get("content").getAsString()).get("ok").getAsBoolean());
        }
    }

    @Test public void cancelledBeforeStartingDoesNotContactModel() throws Exception {
        Events events=new Events();RunControl control=new RunControl();control.cancel();
        try(Server server=new Server((index,request)->new Reply(replyText("unexpected")))) {
            runner(server,(name,args,c)->ok(),events).run("已取消",control);
            assertTrue(server.requests.isEmpty());assertFalse(events.success);assertEquals(1,events.finishes.get());
        }
    }

    @Test(timeout=15000) public void cancellingBlockedHttpCompletesPromptlyWithoutExecutingTools() throws Exception {
        Events events=new Events();RunControl control=new RunControl();CountDownLatch received=new CountDownLatch(1), release=new CountDownLatch(1);
        ExecutorService worker=Executors.newFixedThreadPool(2);
        try(Server server=new Server((index,request)->{received.countDown();release.await(8,TimeUnit.SECONDS);return new Reply(replyTools(tool("late","observe","{}")));})) {
            Future<?> run=worker.submit(()->runner(server,(name,args,c)->{assertEquals("observe",name);return observation("r1");},events).run("随后取消",control));
            assertTrue(received.await(3,TimeUnit.SECONDS));
            Future<?> cancel=worker.submit(control::cancel);
            cancel.get(3,TimeUnit.SECONDS);run.get(3,TimeUnit.SECONDS);
            assertEquals(1,events.finishes.get());assertFalse(events.success);
        } finally { release.countDown();control.cancel();worker.shutdownNow(); }
    }

    @Test public void pausedRunWaitsBeforeContactingTheModel() throws Exception {
        Events events=new Events();RunControl control=new RunControl();control.pause();
        CountDownLatch received=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
        try(Server server=new Server((index,request)->{received.countDown();return new Reply(replyText("等待完成。"));})) {
            Future<?> run=worker.submit(()->runner(server,(name,args,c)->observation("r1"),events).run("暂停任务",control));
            assertFalse(received.await(100,TimeUnit.MILLISECONDS));
            control.resume();run.get(3,TimeUnit.SECONDS);
            assertEquals(1,server.requests.size());assertEquals(1,events.finishes.get());
        } finally {control.cancel();worker.shutdownNow();}
    }

    @Test(timeout=15000) public void pauseDropsLateModelPlanAndResumeObservesWithoutResettingTask() throws Exception {
        Events events=new Events();RunControl control=new RunControl();String taskId=control.getTaskId();long epoch=control.getExecutionEpoch();
        CountDownLatch firstRequest=new CountDownLatch(1), releaseOldResponse=new CountDownLatch(1), secondRequest=new CountDownLatch(1);
        List<String> actions=new CopyOnWriteArrayList<>();AtomicInteger observations=new AtomicInteger();ExecutorService worker=Executors.newFixedThreadPool(2);
        try(Server server=new Server((index,request)-> {
            if(index==0) {
                firstRequest.countDown();releaseOldResponse.await(8,TimeUnit.SECONDS);
                return new Reply(replyTools(tool("old-plan","click","{\"nodeId\":\"n1\",\"expectedRevision\":\"r1\"}")));
            }
            secondRequest.countDown();return new Reply(replyText("根据恢复后的界面重新规划。"));
        })) {
            Future<?> run=worker.submit(()->runner(server,(name,args,c)->{actions.add(name);return observation("r"+observations.incrementAndGet());},events).run("保留这个原始目标",control));
            assertTrue(firstRequest.await(3,TimeUnit.SECONDS));worker.submit(control::pause).get(1,TimeUnit.SECONDS);
            assertTrue(control.isPaused());assertEquals(0,events.finishes.get());
            assertFalse(secondRequest.await(100,TimeUnit.MILLISECONDS));
            control.resume();assertTrue(secondRequest.await(3,TimeUnit.SECONDS));releaseOldResponse.countDown();run.get(3,TimeUnit.SECONDS);
            assertEquals(taskId,control.getTaskId());assertTrue(control.getExecutionEpoch()>epoch);
            assertEquals(Arrays.asList("observe","observe"),actions);
            assertEquals(2,control.budgetSnapshot().modelRounds);assertEquals(2,server.requests.size());
            assertTrue(server.requests.get(1).body.contains("保留这个原始目标"));
            assertTrue(server.requests.get(1).body.contains("r2"));
            assertFalse(server.requests.get(1).body.contains("old-plan"));
            assertEquals(TaskResult.Kind.MODEL_REPORT,events.result.kind);assertEquals(1,events.finishes.get());
        } finally {releaseOldResponse.countDown();control.cancel();worker.shutdownNow();}
    }

    @Test(timeout=15000) public void resumeCannotResetConsumedModelRoundBudget() throws Exception {
        Events events=new Events();RunControl control=new RunControl("current-personal",new TaskBudget(1,10,600000));
        CountDownLatch received=new CountDownLatch(1), release=new CountDownLatch(1);ExecutorService worker=Executors.newFixedThreadPool(2);
        try(Server server=new Server((index,request)->{received.countDown();release.await(8,TimeUnit.SECONDS);return new Reply(replyText("过期结果。"));})) {
            Future<?> run=worker.submit(()->runner(server,(name,args,c)->observation("r1"),events).run("预算保持不变",control));
            assertTrue(received.await(3,TimeUnit.SECONDS));worker.submit(control::pause).get(1,TimeUnit.SECONDS);
            control.resume();run.get(3,TimeUnit.SECONDS);
            assertEquals(1,server.requests.size());assertEquals(1,control.budgetSnapshot().modelRounds);
            assertEquals(TaskResult.Kind.BUDGET_EXCEEDED,events.result.kind);assertEquals(1,events.finishes.get());
        } finally {release.countDown();control.cancel();worker.shutdownNow();}
    }

    @Test public void cancellingInsideToolPreventsFurtherCallsAndReportsCancellation() throws Exception {
        Events events=new Events();RunControl control=new RunControl();List<String> actions=new ArrayList<>();
        try(Server server=new Server((index,request)->new Reply(replyTools(
                tool("a","click","{\"nodeId\":\"n1\",\"expectedRevision\":\"r1\"}"),
                tool("b","click","{\"nodeId\":\"n2\",\"expectedRevision\":\"r1\"}"))))) {
            runner(server,(name,args,c)->{actions.add(name);if(name.equals("click"))c.cancel();return name.equals("observe")?observation("r1"):ok();},events).run("中途停止",control);
            assertEquals(Arrays.asList("observe","click"),actions);assertEquals(1,server.requests.size());assertFalse(events.success);assertEquals(1,events.finishes.get());
            assertEquals(TaskResult.Kind.STOPPED,events.result.kind);
        }
    }

    @Test public void endlessModelLoopStopsAtBoundedNumberOfRounds() throws Exception {
        Events events=new Events();
        try(Server server=new Server((index,request)->new Reply(replyTools(tool("observe-"+index,"observe","{}"))))) {
            runner(server,(name,args,c)->observation("r1"),events).run("无限任务",new RunControl());
            assertFalse(events.success);assertEquals(1,events.finishes.get());
            assertTrue(server.requests.size()>1);assertTrue("model loop must be bounded",server.requests.size()<=30);
            assertEquals(TaskResult.Kind.BUDGET_EXCEEDED,events.result.kind);
        }
    }
}
