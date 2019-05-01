package com.mesosphere.sdk.testing;

import com.mesosphere.sdk.specification.ServiceSpec;
import com.mesosphere.sdk.specification.yaml.RawServiceSpec;
import com.mesosphere.sdk.storage.Persister;

import java.util.Collection;
import java.util.Map;

/**
 * An object which contains the generated results from rendering a Service via {@link ServiceTestRunner}.
 */
public class ServiceTestResult {

  private final ServiceSpec serviceSpec;

  private final RawServiceSpec rawServiceSpec;

  private final Map<String, String> schedulerEnvironment;

  private final Collection<TaskConfig> taskConfigs;

  private final Persister persister;

  private final ClusterState clusterState;

  ServiceTestResult(
      ServiceSpec serviceSpec,
      RawServiceSpec rawServiceSpec,
      Map<String, String> schedulerEnvironment,
      Collection<TaskConfig> taskConfigs,
      Persister persister,
      ClusterState clusterState)
  {
    this.serviceSpec = serviceSpec;
    this.rawServiceSpec = rawServiceSpec;
    this.schedulerEnvironment = schedulerEnvironment;
    this.taskConfigs = taskConfigs;
    this.persister = persister;
    this.clusterState = clusterState;
  }

  /**
   * Returns the {@link ServiceSpec} (translated Service Specification) which was generated by the test.
   */
  public ServiceSpec getServiceSpec() {
    return serviceSpec;
  }

  /**
   * Returns the {@link RawServiceSpec} (object model of a {@code svc.yml}) which was generated by the test.
   */
  public RawServiceSpec getRawServiceSpec() {
    return rawServiceSpec;
  }

  /**
   * Returns the map of environment variables for the Scheduler which were generated by the test.
   */
  public Map<String, String> getSchedulerEnvironment() {
    return schedulerEnvironment;
  }

  /**
   * Returns the specified rendered task config content, or throws {@link IllegalArgumentException} if no such config
   * was found.
   */
  public String getTaskConfig(String podType, String taskName, String configName) {
    for (TaskConfig config : taskConfigs) {
      if (config.podType.equals(podType)
          && config.taskName.equals(taskName)
          && config.configName.equals(configName))
      {
        return config.configContent;
      }
    }
    throw new IllegalArgumentException(String.format(
        "Unable to find config [pod=%s, task=%s, config=%s]. Known configs are: %s",
        podType, taskName, configName, taskConfigs));
  }

  /**
   * Returns the persister/ZK state resulting from the simulation. This may be used to validate the state following a
   * simulation, or may be passed to a later simulation run.
   */
  public Persister getPersister() {
    return persister;
  }

  /**
   * Returns the cluster/task state resulting from the simulation. This may be used to validate the state following a
   * simulation, or may be passed to a later simulation run.
   */
  public ClusterState getClusterState() {
    return clusterState;
  }

  /**
   * An internal-only object for the result of generating a config file.
   */
  static class TaskConfig {
    private final String podType;

    private final String taskName;

    private final String configName;

    private final String configContent;

    TaskConfig(String podType, String taskName, String configName, String configContent) {
      this.podType = podType;
      this.taskName = taskName;
      this.configName = configName;
      this.configContent = configContent;
    }

    @Override
    public String toString() {
      return String.format("%s-%s: %s (%d bytes)", podType, taskName, configName, configContent.length());
    }
  }
}