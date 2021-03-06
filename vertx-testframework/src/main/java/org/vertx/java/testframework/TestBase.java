/*
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.vertx.java.testframework;

import junit.framework.TestCase;
import org.junit.Test;
import org.vertx.java.core.Handler;
import org.vertx.java.core.SimpleHandler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;
import org.vertx.java.platform.PlatformLocator;
import org.vertx.java.platform.PlatformManager;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class TestBase extends TestCase {

  private static final Logger log = LoggerFactory.getLogger(TestBase.class);
  private static final int DEFAULT_TIMEOUT = Integer.parseInt(System.getProperty("vertx.test.timeout", "30"));

  public static final String EVENTS_ADDRESS = "__test_events";

  // A single Vertx and DefaultPlatformManager for <b>ALL</b> tests
  private static PlatformManager platformManager = PlatformLocator.factory.createPlatformManager();
  protected static Vertx vertx = platformManager.getVertx();

  private BlockingQueue<JsonObject> events = new LinkedBlockingQueue<>();
  private TestUtils tu = new TestUtils(vertx);
  private volatile Handler<Message<JsonObject>> handler;
  private List<AssertHolder> failedAsserts = new ArrayList<>();
  private List<String> startedApps = new CopyOnWriteArrayList<>();

  private class AssertHolder {
    final String message;
    final String stackTrace;

    private AssertHolder(String message, String stackTrace) {
      this.message = message;
      this.stackTrace = stackTrace;
    }
  }

  private void throwAsserts() {
    for (AssertHolder holder: failedAsserts) {
      fail(holder.message + "\n" + holder.stackTrace);
    }
    failedAsserts.clear();
  }

  @Override
  protected void setUp() throws Exception {
    EventLog.clear();
    handler = new Handler<Message<JsonObject>>() {
      public void handle(Message<JsonObject> message) {
        try {

          String type = message.body.getString("type");

          switch (type) {
            case EventFields.TRACE_EVENT:
              log.trace(message.body.getString(EventFields.TRACE_MESSAGE_FIELD));
              break;
            case EventFields.EXCEPTION_EVENT:
              failedAsserts.add(new AssertHolder(message.body.getString(EventFields.EXCEPTION_MESSAGE_FIELD),
                  message.body.getString(EventFields.EXCEPTION_STACKTRACE_FIELD)));
              break;
            case EventFields.ASSERT_EVENT:
              boolean passed = EventFields.ASSERT_RESULT_VALUE_PASS.equals(message.body.getString(EventFields.ASSERT_RESULT_FIELD));
              if (passed) {
              } else {
                failedAsserts.add(new AssertHolder(message.body.getString(EventFields.ASSERT_MESSAGE_FIELD),
                    message.body.getString(EventFields.ASSERT_STACKTRACE_FIELD)));
              }
              break;
            case EventFields.START_TEST_EVENT:
              //Ignore
              break;
            case EventFields.APP_STOPPED_EVENT:
              events.add(message.body);
              break;
            case EventFields.APP_READY_EVENT:
              events.add(message.body);
              break;
            case EventFields.TEST_COMPLETE_EVENT:
              events.add(message.body);
              break;
            default:
              throw new IllegalArgumentException("Invalid type: " + type);
          }

        } catch (Exception e) {
          log.error("Failed to parse JSON", e);
        }
      }
    };

    vertx.eventBus().registerHandler(EVENTS_ADDRESS, handler);
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      throwAsserts();
    } finally {
      try {
        List<String> apps = new ArrayList<>(startedApps);
        for (String appName: apps) {
          stopApp(appName);
        }
        events.clear();
        vertx.eventBus().unregisterHandler(EVENTS_ADDRESS, handler);
      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      }
    }
    if (platformManager.checkNoModules() > 0) {
      fail("Module references remain after test");
    }
    //EventLog.addEvent("teardown complete");
    //EventLog.clear();
  }

  protected String startApp(String main) throws Exception {
    return startApp(false, main, true);
  }

  protected String startApp(String main, JsonObject config) throws Exception {
    return startApp(false, main, config, 1, true);
  }

  protected String startApp(String main, boolean await) throws Exception {
    return startApp(false, main, null, 1, await);
  }

  protected String startApp(String main, int instances) throws Exception {
    return startApp(false, main, null, instances, true);
  }

  protected String startApp(boolean worker, String main) throws Exception {
    return startApp(worker, main, true);
  }

  protected String startApp(boolean worker, String main, JsonObject config) throws Exception {
    return startApp(worker, main, config, 1, true);
  }

  protected String startApp(boolean worker, String main, boolean await) throws Exception {
    return startApp(worker, main, null, 1, await);
  }

  protected String startApp(boolean worker, String main, int instances) throws Exception {
    return startApp(worker, main, null, instances, true);
  }

  protected String startApp(boolean worker, String main, JsonObject config, int instances, boolean await) throws Exception {
    //EventLog.addEvent("Starting app " + main);
    URL url;
    if (main.endsWith(".js") || main.endsWith(".rb") || main.endsWith(".groovy") || main.endsWith(".py")) {
      url = getClass().getClassLoader().getResource(main);
    } else {
      String classDir = main.replace('.', '/') + ".class";
      url = getClass().getClassLoader().getResource(classDir);
      String surl = url.toString();
      String surlroot = surl.substring(0, surl.length() - classDir.length());
      url = new URL(surlroot);
    }

    if (url == null) {
      throw new IllegalArgumentException("Cannot find verticle: " + main);
    }

    final CountDownLatch doneLatch = new CountDownLatch(1);
    final AtomicReference<String> res = new AtomicReference<>();

    Handler<String> doneHandler = new Handler<String>() {
      public void handle(String deploymentName) {
        if (deploymentName != null) {
          startedApps.add(deploymentName);
        }
        res.set(deploymentName);
        doneLatch.countDown();
      }
    };

    if (worker) {
      platformManager.deployWorkerVerticle(false, main, config, new URL[]{url}, instances, null, doneHandler);
    } else {
      platformManager.deployVerticle(main, config, new URL[]{url}, instances, null, doneHandler);
    }

    if (!doneLatch.await(30, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out waiting for apps to start");
    }

    //EventLog.addEvent("App deployed");

    String deployID = res.get();

    if (deployID != null && await) {
      for (int i = 0; i < instances; i++) {
        waitAppReady();
        //EventLog.addEvent("App is ready");
      }
    }

    return deployID;
  }

  public String startMod(String modName) throws Exception {
    return startMod(modName, null, 1, true);
  }

  public String startMod(String modName, JsonObject config, int instances, boolean await) throws Exception {

    final CountDownLatch doneLatch = new CountDownLatch(1);
    final AtomicReference<String> res = new AtomicReference<>(null);

    Handler<String> doneHandler = new Handler<String>() {
      public void handle(String deploymentName) {
        if (deploymentName != null) {
          startedApps.add(deploymentName);
        }
        res.set(deploymentName);
        doneLatch.countDown();
      }
    };

    platformManager.deployModule(modName, config, instances, doneHandler);

    if (!doneLatch.await(30, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timedout waiting for apps to start");
    }

    String deployID = res.get();

    if (deployID != null && await) {
      for (int i = 0; i < instances; i++) {
        waitAppReady();
      }
    }

    return deployID;
  }

  protected void stopApp(String appName) throws Exception {
    //EventLog.addEvent("Stopping app " + appName);
    final CountDownLatch latch = new CountDownLatch(1);
    int instances = platformManager.listInstances().get(appName);
    platformManager.undeploy(appName, new SimpleHandler() {
      public void handle() {
        latch.countDown();
      }
    });
    if (!latch.await(30, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timedout waiting for app to stop");
    }
    //EventLog.addEvent("App is undeployed");
    for (int i = 0; i < instances; i++) {
      waitAppStopped();
    }
    //EventLog.addEvent("Waited for app to stop");
    startedApps.remove(appName);
  }

  protected void startTest(String testName) {
    startTest(testName, true);
  }

  protected void startTest(String testName, boolean wait) {
    log.info("Starting test: " + testName);
    //EventLog.addEvent("Starting test " + testName);
    tu.startTest(testName);
    if (wait) {
      //EventLog.addEvent("Waiting for test to complete");
      waitTestComplete();
      //EventLog.addEvent("Test is now complete");
    }
  }

  protected String getMethodName() {
    return Thread.currentThread().getStackTrace()[2].getMethodName();
  }

  protected void waitAppReady() {
    waitAppReady(DEFAULT_TIMEOUT);
  }

  protected void waitAppStopped() {
    waitAppStopped(DEFAULT_TIMEOUT);
  }

  protected void waitAppReady(int timeout) {
    waitEvent(timeout, EventFields.APP_READY_EVENT);
  }

  protected void waitAppStopped(int timeout) {
    waitEvent(timeout, EventFields.APP_STOPPED_EVENT);
  }

  protected void waitTestComplete(int timeout) {
    waitEvent(timeout, EventFields.TEST_COMPLETE_EVENT);
  }

  protected void waitEvent(String eventName) {
    waitEvent(5, eventName);
  }

  protected void waitEvent(int timeout, String eventName) {

    JsonObject message;
    while (true) {
      try {
        message = events.poll(timeout, TimeUnit.SECONDS);
        break;
      } catch (InterruptedException cont) {
      }
    }

    if (message == null) {
      EventLog.dump();
      throw new IllegalStateException("Timed out waiting for event");
    }

    if (!eventName.equals(message.getString("type"))) {
      throw new IllegalStateException("Expected event: " + eventName + " got: " + message.getString(EventFields.TYPE_FIELD));
    }
  }

  protected void waitTestComplete() {
    waitTestComplete(DEFAULT_TIMEOUT);
  }

  @Test
  protected void runTestInLoop(String testName, int iters) throws Exception {
    Method meth = getClass().getMethod(testName, (Class<?>[])null);
    for (int i = 0; i < iters; i++) {
      log.info("****************************** ITER " + i);
      meth.invoke(this);
      tearDown();
      if (i != iters - 1) {
        setUp();
      }
    }
  }


}
