package com.metamx.druid.merger.coordinator;

import com.google.common.base.Throwables;
import com.metamx.common.lifecycle.Lifecycle;
import com.metamx.common.lifecycle.LifecycleStart;
import com.metamx.common.lifecycle.LifecycleStop;
import com.metamx.druid.initialization.Initialization;
import com.metamx.druid.initialization.ServiceDiscoveryConfig;
import com.metamx.druid.merger.coordinator.config.IndexerCoordinatorConfig;
import com.metamx.druid.merger.coordinator.exec.TaskConsumer;
import com.metamx.emitter.EmittingLogger;
import com.metamx.emitter.service.ServiceEmitter;
import com.netflix.curator.framework.CuratorFramework;
import com.netflix.curator.framework.recipes.leader.LeaderSelector;
import com.netflix.curator.framework.recipes.leader.LeaderSelectorListener;
import com.netflix.curator.framework.state.ConnectionState;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Encapsulates the indexer leadership lifecycle.
 */
public class TaskMaster
{
  private final LeaderSelector leaderSelector;
  private final ReentrantLock giant = new ReentrantLock();
  private final Condition mayBeStopped = giant.newCondition();

  private volatile boolean leading = false;

  private static final EmittingLogger log = new EmittingLogger(TaskMaster.class);

  public TaskMaster(
      final TaskQueue queue,
      final IndexerCoordinatorConfig indexerCoordinatorConfig,
      final ServiceDiscoveryConfig serviceDiscoveryConfig,
      final MergerDBCoordinator mergerDBCoordinator,
      final TaskRunnerFactory runnerFactory,
      final CuratorFramework curator,
      final ServiceEmitter emitter
      )
  {
    this.leaderSelector = new LeaderSelector(
        curator, indexerCoordinatorConfig.getLeaderLatchPath(), new LeaderSelectorListener()
    {
      @Override
      public void takeLeadership(CuratorFramework client) throws Exception
      {
        giant.lock();

        try {
          log.info("By the power of Grayskull, I have the power!");

          final TaskRunner runner = runnerFactory.build();
          final TaskConsumer consumer = new TaskConsumer(queue, runner, mergerDBCoordinator, emitter);

          // Sensible order to start stuff:
          final Lifecycle leaderLifecycle = new Lifecycle();
          leaderLifecycle.addManagedInstance(queue);
          leaderLifecycle.addManagedInstance(runner);
          Initialization.makeServiceDiscoveryClient(curator, serviceDiscoveryConfig, leaderLifecycle);
          leaderLifecycle.addManagedInstance(consumer);
          leaderLifecycle.start();

          leading = true;

          try {
            while (leading) {
              mayBeStopped.await();
            }
          } finally {
            log.info("Bowing out!");
            leaderLifecycle.stop();
          }
        } catch(Exception e) {
          log.makeAlert(e, "Failed to lead").emit();
          throw Throwables.propagate(e);
        } finally {
          giant.unlock();
        }
      }

      @Override
      public void stateChanged(CuratorFramework client, ConnectionState newState)
      {
        if (newState == ConnectionState.LOST || newState == ConnectionState.SUSPENDED) {
          // disconnected from zk. assume leadership is gone
          stopLeading();
        }
      }
    }
    );

    leaderSelector.setId(indexerCoordinatorConfig.getServerName());
    leaderSelector.autoRequeue();
  }

  /**
   * Starts waiting for leadership. Should only be called once throughout the life of the program.
   */
  @LifecycleStart
  public void start()
  {
    giant.lock();

    try {
      leaderSelector.start();
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Stops forever (not just this particular leadership session). Should only be called once throughout the life of
   * the program.
   */
  @LifecycleStop
  public void stop()
  {
    giant.lock();

    try {
      leaderSelector.close();
      stopLeading();
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Relinquish leadership. May be called multiple times, even when not currently the leader.
   */
  private void stopLeading()
  {
    giant.lock();

    try {
      if (leading) {
        leading = false;
        mayBeStopped.signalAll();
      }
    }
    finally {
      giant.unlock();
    }
  }

  public boolean isLeading()
  {
    return leading;
  }

  public String getLeader()
  {
    try {
      return leaderSelector.getLeader().getId();
    }
    catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }
}
