/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.util.concurrency;

import com.intellij.diagnostic.ThreadDumper;
import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.util.TimeoutUtil;
import com.intellij.util.containers.ContainerUtil;
import junit.framework.TestCase;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public class AppScheduledExecutorServiceTest extends TestCase {
  private static class LogInfo {
    private final int runnable;
    private final Thread currentThread;

    private LogInfo(int runnable) {
      this.runnable = runnable;
      currentThread = Thread.currentThread();
    }
  }

  private AppScheduledExecutorService service;
  @Override
  protected void setUp() throws Exception {
    super.setUp();
    service = new AppScheduledExecutorService(getName());
  }

  @Override
  protected void tearDown() throws Exception {
    service.shutdownAppScheduledExecutorService();
    assertTrue(service.awaitTermination(10, TimeUnit.SECONDS));
    super.tearDown();
  }

  public void testDelayedWorks() throws InterruptedException, TimeoutException {
    assertFalse(service.isShutdown());
    assertFalse(service.isTerminated());

    service.invokeAll(Collections.nCopies(service.getBackendPoolCorePoolSize() + 1, Executors.callable(EmptyRunnable.getInstance()))); // pre-start all threads

    int delay = 1000;
    long start = System.currentTimeMillis();
    List<LogInfo> log = Collections.synchronizedList(new ArrayList<>());
    ScheduledFuture<?> f1 = service.schedule(() -> {
      log.add(new LogInfo(1));
      TimeoutUtil.sleep(10);
    }, delay, TimeUnit.MILLISECONDS);
    assertFalse(service.isShutdown());
    assertFalse(service.isTerminated());
    ScheduledFuture<?> f2 = service.schedule(() -> {
      log.add(new LogInfo(2));
      TimeoutUtil.sleep(10);
    }, delay, TimeUnit.MILLISECONDS);
    assertFalse(service.isShutdown());
    assertFalse(service.isTerminated());
    ScheduledFuture<?> f3 = service.schedule(() -> {
      log.add(new LogInfo(3));
      TimeoutUtil.sleep(10);
    }, delay, TimeUnit.MILLISECONDS);

    assertFalse(service.isShutdown());
    assertFalse(service.isTerminated());
    Future<?> f4 = service.submit((Runnable)() -> log.add(new LogInfo(4)));

    assertFalse(service.isShutdown());
    assertFalse(service.isTerminated());
    assertFalse(f1.isDone());
    assertFalse(f2.isDone());
    assertFalse(f3.isDone());

    TimeoutUtil.sleep(delay/2);
    long elapsed = System.currentTimeMillis() - start; // can be > delay/2 on overloaded agent
    assertEquals(String.valueOf(f1.isDone()), elapsed > delay, f1.isDone());
    assertEquals(String.valueOf(f2.isDone()), elapsed > delay, f2.isDone());
    assertEquals(String.valueOf(f3.isDone()), elapsed > delay, f3.isDone());
    assertTrue(f4.isDone());

    TimeoutUtil.sleep(delay/2);
    waitFor(f1::isDone);
    waitFor(f2::isDone);
    waitFor(f3::isDone);
    waitFor(f4::isDone);

    assertEquals(4, log.size());
    assertEquals(4, log.get(0).runnable);
    List<Thread> threads = Arrays.asList(log.get(1).currentThread, log.get(2).currentThread, log.get(3).currentThread);
    assertEquals(threads.toString(), 3, new HashSet<>(threads).size()); // must be executed in parallel
  }

  public void testMustNotBeAbleToShutdown() {
    try {
      service.shutdown();
      fail();
    }
    catch (Exception ignored) {
    }
    try {
      service.shutdownNow();
      fail();
    }
    catch (Exception ignored) {
    }
  }

  public void testMustNotBeAbleToShutdownGlobalPool() {
    ExecutorService service = AppExecutorUtil.getAppExecutorService();
    try {
      service.shutdown();
      fail();
    }
    catch (Exception ignored) {
    }
    try {
      service.shutdownNow();
      fail();
    }
    catch (Exception ignored) {
    }
    try {
      ((ThreadPoolExecutor)service).setThreadFactory(Thread::new);
      fail();
    }
    catch (Exception ignored) {
    }
    try {
      ((ThreadPoolExecutor)service).setCorePoolSize(0);
      fail();
    }
    catch (Exception ignored) {
    }
  }

  public void testDelayedTasksReusePooledThreadIfExecuteAtDifferentTimes() throws Exception {
    // pre-start one thread
    Future<?> future = service.submit(EmptyRunnable.getInstance());
    future.get();
    service.setBackendPoolCorePoolSize(1);
    assertEquals(1, service.getBackendPoolExecutorSize());

    int delay = 500;

    List<LogInfo> log = Collections.synchronizedList(new ArrayList<>());
    ScheduledFuture<?> f1 = service.schedule((Runnable)() -> log.add(new LogInfo(1)), delay, TimeUnit.MILLISECONDS);
    ScheduledFuture<?> f2 = service.schedule((Runnable)() -> log.add(new LogInfo(2)), delay + 100, TimeUnit.MILLISECONDS);
    ScheduledFuture<?> f3 = service.schedule((Runnable)() -> log.add(new LogInfo(3)), delay + 200, TimeUnit.MILLISECONDS);

    assertEquals(1, service.getBackendPoolExecutorSize());

    assertFalse(f1.isDone());
    assertFalse(f2.isDone());
    assertFalse(f3.isDone());

    TimeoutUtil.sleep(delay+200+300);
    waitFor(f1::isDone);

    waitFor(f2::isDone);
    waitFor(f3::isDone);
    assertEquals(1, service.getBackendPoolExecutorSize());

    assertEquals(3, log.size());
    Set<Thread> usedThreads = new HashSet<>(Arrays.asList(log.get(0).currentThread, log.get(1).currentThread, log.get(2).currentThread));
    if (usedThreads.size() != 1) {
      System.err.println(ThreadDumper.dumpThreadsToString());
    }
    assertEquals(usedThreads.toString(), 1, usedThreads.size()); // must be executed in same thread
  }

  public void testDelayedTasksThatFiredAtTheSameTimeAreExecutedConcurrently() throws InterruptedException, ExecutionException {
    final List<LogInfo> log = Collections.synchronizedList(new ArrayList<>());
    int delay = 500;

    int N = 20;
    List<? extends Future<?>> futures =
      ContainerUtil.map(Collections.nCopies(N, ""), __ -> service.schedule(()-> {
        log.add(new LogInfo(0));
        TimeoutUtil.sleep(10 * 1000);
      }
        , delay, TimeUnit.MILLISECONDS
      ));

    for (Future<?> future : futures) {
      future.get();
    }

    assertEquals(N, log.size());
    Set<Thread> usedThreads = ContainerUtil.map2Set(log, logInfo -> logInfo.currentThread);

    assertEquals(N, usedThreads.size());
  }

  public void testAwaitTerminationMakesSureTasksTransferredToBackendExecutorAreFinished() throws InterruptedException, ExecutionException {
    final AppScheduledExecutorService service = new AppScheduledExecutorService(getName());
    final List<LogInfo> log = Collections.synchronizedList(new ArrayList<>());

    int N = 20;
    int delay = 500;
    List<? extends Future<?>> futures =
      ContainerUtil.map(Collections.nCopies(N, ""), s -> service.schedule(() -> {
          TimeoutUtil.sleep(5000);
          log.add(new LogInfo(0));
        }, delay, TimeUnit.MILLISECONDS
      ));
    TimeoutUtil.sleep(delay);
    long start = System.currentTimeMillis();
    while (!service.delayQueue.isEmpty()) {
      // wait till all tasks transferred to backend
      if (System.currentTimeMillis() > start + 20000) throw new AssertionError("Not transferred after 20 seconds");
    }
    List<SchedulingWrapper.MyScheduledFutureTask> queuedTasks = new ArrayList<>(service.delayQueue);
    if (!queuedTasks.isEmpty()) {
      String s = queuedTasks.stream().map(BoundedTaskExecutor::info).collect(Collectors.toList()).toString();
      fail("Queued tasks left: "+s + ";\n"+queuedTasks);
    }
    service.shutdownAppScheduledExecutorService();
    assertTrue(service.awaitTermination(20, TimeUnit.SECONDS));

    for (Future<?> future : futures) {
      assertTrue(future.isDone());
    }
    assertEquals(log.toString(), N, log.size());
  }

  private static void waitFor(@NotNull BooleanSupplier runnable) throws TimeoutException {
    long start = System.currentTimeMillis();
    while (System.currentTimeMillis() < start + 60000) {
      if (runnable.getAsBoolean()) return;
    }
    throw new TimeoutException();
  }
}
