package dev.agentos.core;

import com.google.gson.JsonArray;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class HttpModelClientTest {
    /** Data keeps arriving, so a per-read timeout alone cannot bound this request. */
    @Test(timeout=6000) public void continuousResponseBytesCannotExtendTotalRequestDeadline() throws Exception {
        AtomicInteger requests=new AtomicInteger();ExecutorService worker=Executors.newCachedThreadPool();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(worker);
        CountDownLatch finished=new CountDownLatch(1);
        server.createContext("/v1/chat/completions",exchange->{
            requests.incrementAndGet();exchange.getRequestBody().readAllBytes();
            try {
                exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().write("{\"result\":\"".getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();
                for(int i=0;i<80;i++) {
                    if(finished.await(30,TimeUnit.MILLISECONDS))break;
                    exchange.getResponseBody().write('x');exchange.getResponseBody().flush();
                }
            } catch(IOException ignored) {} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });server.start();
        RunControl control=new RunControl();
        try {
            ModelSettings settings=new ModelSettings("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","secret","model");
            long start=System.nanoTime();
            ModelException error=assertThrows(ModelException.class,()->new HttpModelClient(250).requestSynthetic(settings,new JsonArray(),new JsonArray(),control,control.getExecutionEpoch()));
            assertEquals("TIMEOUT",error.code);assertEquals(1,requests.get());
            assertTrue("deadline must apply despite steady progress",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500);
        } finally {finished.countDown();control.cancel();server.stop(0);worker.shutdownNow();}
    }

    @Test(timeout=5000) public void remainingTaskBudgetAlsoBoundsInFlightModelRequest() throws Exception {
        ExecutorService worker=Executors.newCachedThreadPool();CountDownLatch release=new CountDownLatch(1);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(worker);
        server.createContext("/",exchange->{try {exchange.getRequestBody().readAllBytes();release.await(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        RunControl control=new RunControl("personal-test",new TaskBudget(30,100,250));
        try {
            ModelSettings settings=new ModelSettings("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","secret","model");
            TaskControlException error=assertThrows(TaskControlException.class,()->new HttpModelClient(1000).requestSynthetic(settings,new JsonArray(),new JsonArray(),control,control.getExecutionEpoch()));
            assertEquals("BUDGET_EXCEEDED",error.getCode());
        } finally {release.countDown();control.cancel();server.stop(0);worker.shutdownNow();}
    }
}
