package com.mesosphere.dcos.cassandra.scheduler.plan;

import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.io.Resources;
import com.mesosphere.dcos.cassandra.common.config.ClusterTaskConfig;
import com.mesosphere.dcos.cassandra.common.tasks.CassandraContainer;
import com.mesosphere.dcos.cassandra.common.tasks.CassandraDaemonTask;
import com.mesosphere.dcos.cassandra.common.tasks.CassandraMode;
import com.mesosphere.dcos.cassandra.common.tasks.CassandraTaskExecutor;
import com.mesosphere.dcos.cassandra.scheduler.TestUtils;
import com.mesosphere.dcos.cassandra.scheduler.client.SchedulerClient;
import com.mesosphere.dcos.cassandra.scheduler.config.*;
import com.mesosphere.dcos.cassandra.scheduler.offer.PersistentOfferRequirementProvider;
import com.mesosphere.dcos.cassandra.scheduler.tasks.CassandraState;
import io.dropwizard.configuration.ConfigurationFactory;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.FileConfigurationSourceProvider;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.validation.BaseValidator;
import org.apache.curator.RetryPolicy;
import org.apache.curator.retry.RetryForever;
import org.apache.curator.retry.RetryUntilElapsed;
import org.apache.curator.test.TestingServer;
import org.apache.mesos.Protos;
import org.apache.mesos.curator.CuratorStateStore;
import org.apache.mesos.dcos.Capabilities;
import org.apache.mesos.offer.OfferRequirement;
import org.apache.mesos.scheduler.plan.Block;
import org.apache.mesos.scheduler.plan.Status;
import org.apache.mesos.state.StateStore;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

public class CassandraDaemonBlockTest {
    @Mock
    private PersistentOfferRequirementProvider persistentOfferRequirementProvider;
    @Mock
    private CassandraState cassandraState;
    @Mock
    private SchedulerClient client;
    @Mock
    private CompletionStage<Boolean> mockStage;
    @Mock
    private CompletableFuture<Boolean> mockFuture;

    private static TestingServer server;
    private static CassandraDaemonTask.Factory taskFactory;
    private static MutableSchedulerConfiguration config;
    private static ClusterTaskConfig clusterTaskConfig;
    private static StateStore stateStore;
    private static DefaultConfigurationManager configurationManager;

    @Before
    public void beforeEach() throws Exception {
        MockitoAnnotations.initMocks(this);
        server = new TestingServer();
        server.start();

        Capabilities mockCapabilities = Mockito.mock(Capabilities.class);
        when(mockCapabilities.supportsNamedVips()).thenReturn(true);
        taskFactory = new CassandraDaemonTask.Factory(mockCapabilities);

        final ConfigurationFactory<MutableSchedulerConfiguration> factory =
                new ConfigurationFactory<>(
                        MutableSchedulerConfiguration.class,
                        BaseValidator.newValidator(),
                        Jackson.newObjectMapper().registerModule(
                                new GuavaModule())
                                .registerModule(new Jdk8Module()),
                        "dw");

        config = factory.build(
                new SubstitutingSourceProvider(
                        new FileConfigurationSourceProvider(),
                        new EnvironmentVariableSubstitutor(false, true)),
                Resources.getResource("scheduler.yml").getFile());

        final CassandraSchedulerConfiguration targetConfig = config.createConfig();
        clusterTaskConfig = targetConfig.getClusterTaskConfig();

        final CuratorFrameworkConfig curatorConfig = config.getCuratorConfig();
        RetryPolicy retryPolicy =
                (curatorConfig.getOperationTimeout().isPresent()) ?
                        new RetryUntilElapsed(
                                curatorConfig.getOperationTimeoutMs()
                                        .get()
                                        .intValue()
                                , (int) curatorConfig.getBackoffMs()) :
                        new RetryForever((int) curatorConfig.getBackoffMs());

        stateStore = new CuratorStateStore(
                targetConfig.getServiceConfig().getName(),
                server.getConnectString(),
                retryPolicy);
        stateStore.storeFrameworkId(Protos.FrameworkID.newBuilder().setValue("1234").build());
        configurationManager = new DefaultConfigurationManager(CassandraSchedulerConfiguration.class,
                        config.createConfig().getServiceConfig().getName(),
                        server.getConnectString(),
                        config.createConfig(),
                        new ConfigValidator(),
                        stateStore);

        cassandraState = new CassandraState(
                new ConfigurationManager(taskFactory, configurationManager),
                curatorConfig,
                clusterTaskConfig,
                stateStore);
    }

    @Test
    public void testCreate() throws Exception {
        final CassandraContainer cassandraContainer = Mockito.mock(CassandraContainer.class);
        final CassandraDaemonTask daemonTask = Mockito.mock(CassandraDaemonTask.class);
        final String EXPECTED_NAME = "node-0";
        when(cassandraContainer.getDaemonTask()).thenReturn(daemonTask);
        when(daemonTask.getName()).thenReturn(EXPECTED_NAME);
        CassandraDaemonBlock block = CassandraDaemonBlock.create(
                EXPECTED_NAME, persistentOfferRequirementProvider, cassandraState, client);

        Assert.assertEquals(EXPECTED_NAME, block.getName());
        Assert.assertEquals(Status.PENDING, Block.getStatus(block));
    }

    @Test
    public void testStart() throws Exception {
        final String EXPECTED_NAME = "node-0";
        CassandraDaemonBlock block = CassandraDaemonBlock.create(
                EXPECTED_NAME, persistentOfferRequirementProvider, cassandraState, client);

        final OfferRequirement mockOfferReq = Mockito.mock(OfferRequirement.class);
        when(persistentOfferRequirementProvider.getNewOfferRequirement(Mockito.any())).thenReturn(
                Optional.of(mockOfferReq));

        final Optional<OfferRequirement> offerRequirement = block.start();
        Assert.assertTrue(offerRequirement.isPresent());
    }

    @Test
    public void testStartCompleted() throws Exception {
        final String EXPECTED_NAME = "node-0";
        CassandraDaemonBlock block = CassandraDaemonBlock.create(
                EXPECTED_NAME, persistentOfferRequirementProvider, cassandraState, client);

        final OfferRequirement mockOfferReq = Mockito.mock(OfferRequirement.class);
        when(persistentOfferRequirementProvider.getNewOfferRequirement(Mockito.any())).thenReturn(
                Optional.of(mockOfferReq));

        Assert.assertTrue(block.start().isPresent());

        final CassandraDaemonTask task = taskFactory.create(EXPECTED_NAME,
                "abc",
                CassandraTaskExecutor.create("1234", EXPECTED_NAME, "cassandra-role", "cassandra-principal", config.getExecutorConfig()),
                config.getCassandraConfig());
        Protos.TaskInfo taskInfo = task.getTaskInfo();
        taskInfo = Protos.TaskInfo.newBuilder(taskInfo)
                .setSlaveId(Protos.SlaveID.newBuilder().setValue("1.2.3.4").build()).build();
        stateStore.storeTasks(Arrays.asList(taskInfo));

        final Protos.TaskStatus status = TestUtils.generateStatus(taskInfo.getTaskId(),
                Protos.TaskState.TASK_RUNNING, CassandraMode.NORMAL);
        stateStore.storeStatus(status);

        Assert.assertTrue(!block.start().isPresent());
    }

    @Test
    public void testStartCompletedReconcile() throws Exception {
        final String EXPECTED_NAME = "node-0";
        CassandraDaemonBlock block = CassandraDaemonBlock.create(
                EXPECTED_NAME, persistentOfferRequirementProvider, cassandraState, client);

        final OfferRequirement mockOfferReq = Mockito.mock(OfferRequirement.class);
        when(persistentOfferRequirementProvider.getNewOfferRequirement(Mockito.any())).thenReturn(
                Optional.of(mockOfferReq));

        Assert.assertTrue(block.start().isPresent());

        final CassandraDaemonTask task = taskFactory.create(EXPECTED_NAME,
                "abc",
                CassandraTaskExecutor.create("1234", EXPECTED_NAME, "cassandra-role", "cassandra-principal", config.getExecutorConfig()),
                config.getCassandraConfig());
        Protos.TaskInfo taskInfo = task.getTaskInfo();
        taskInfo = Protos.TaskInfo.newBuilder(taskInfo)
                .setSlaveId(Protos.SlaveID.newBuilder().setValue("1.2.3.4").build()).build();
        stateStore.storeTasks(Arrays.asList(taskInfo));

        final Protos.TaskStatus status = TestUtils.generateStatus(taskInfo.getTaskId(), Protos.TaskState.TASK_RUNNING);
        stateStore.storeStatus(status);

        Assert.assertTrue(!block.start().isPresent());
    }

    @Test
    public void testStartNeedConfigUpdateNotTerminated1() throws Exception {
        final String EXPECTED_NAME = "node-0";
        CassandraDaemonBlock block = CassandraDaemonBlock.create(
                EXPECTED_NAME, persistentOfferRequirementProvider, cassandraState, client);

        final OfferRequirement mockOfferReq = Mockito.mock(OfferRequirement.class);
        when(persistentOfferRequirementProvider.getNewOfferRequirement(Mockito.any())).thenReturn(
                Optional.of(mockOfferReq));

        Assert.assertTrue(block.start().isPresent());

        final CassandraDaemonTask task = taskFactory.create(EXPECTED_NAME,
                "abc",
                CassandraTaskExecutor.create("1234", EXPECTED_NAME, "cassandra-role", "cassandra-principal", config.getExecutorConfig()),
                config.getCassandraConfig());
        Protos.TaskInfo taskInfo = task.getTaskInfo();
        taskInfo = Protos.TaskInfo.newBuilder(taskInfo)
                .setSlaveId(Protos.SlaveID.newBuilder().setValue("1.2.3.4").build()).build();
        stateStore.storeTasks(Arrays.asList(taskInfo));

        Protos.TaskStatus status = TestUtils.generateStatus(taskInfo.getTaskId(),
                Protos.TaskState.TASK_RUNNING, CassandraMode.NORMAL);
        stateStore.storeStatus(status);

        Assert.assertTrue(!block.start().isPresent());

        final CompletionStage mockStage = Mockito.mock(CompletionStage.class);
        final CompletableFuture mockFuture = Mockito.mock(CompletableFuture.class);
        when(mockStage.toCompletableFuture()).thenReturn(mockFuture);
        when(mockFuture.get()).thenReturn(true);
        when(client.shutdown("1234", 1234)).thenReturn(mockStage);

        status = TestUtils.generateStatus(taskInfo.getTaskId(),
                Protos.TaskState.TASK_FINISHED);
        stateStore.storeStatus(status);

        when(persistentOfferRequirementProvider.getReplacementOfferRequirement(Mockito.any())).thenReturn(
                Optional.empty());
        Assert.assertTrue(!block.start().isPresent());
    }

    @Test
    public void testStartNeedConfigUpdateNotTerminated2() throws Exception {
        final String EXPECTED_NAME = "node-0";
        CassandraDaemonBlock block = CassandraDaemonBlock.create(
                EXPECTED_NAME, persistentOfferRequirementProvider, cassandraState, client);

        final OfferRequirement mockOfferReq = Mockito.mock(OfferRequirement.class);
        when(persistentOfferRequirementProvider.getNewOfferRequirement(Mockito.any())).thenReturn(
                Optional.of(mockOfferReq));

        Assert.assertTrue(block.start().isPresent());

        final CassandraDaemonTask task = taskFactory.create(EXPECTED_NAME,
                "abc",
                CassandraTaskExecutor.create("1234", EXPECTED_NAME, "cassandra-role", "cassandra-principal", config.getExecutorConfig()),
                config.getCassandraConfig());
        Protos.TaskInfo taskInfo = task.getTaskInfo();
        taskInfo = Protos.TaskInfo.newBuilder(taskInfo)
                .setSlaveId(Protos.SlaveID.newBuilder().setValue("1.2.3.4").build()).build();
        stateStore.storeTasks(Arrays.asList(taskInfo));

        Protos.TaskStatus status = TestUtils.generateStatus(taskInfo.getTaskId(),
                Protos.TaskState.TASK_RUNNING, CassandraMode.NORMAL);
        stateStore.storeStatus(status);

        Assert.assertTrue(!block.start().isPresent());
        final CompletionStage mockStage = Mockito.mock(CompletionStage.class);
        final CompletableFuture mockFuture = Mockito.mock(CompletableFuture.class);
        when(mockStage.toCompletableFuture()).thenReturn(mockFuture);
        when(mockFuture.get()).thenReturn(false);
        when(client.shutdown("1234", 1234)).thenReturn(mockStage);

        status = TestUtils.generateStatus(taskInfo.getTaskId(),
                Protos.TaskState.TASK_FINISHED);
        stateStore.storeStatus(status);

        when(persistentOfferRequirementProvider.getReplacementOfferRequirement(Mockito.any())).thenReturn(
                Optional.empty());
        Assert.assertTrue(!block.start().isPresent());
    }

    @Test
    public void testStartNeedConfigUpdateTerminated() throws Exception {
        final String EXPECTED_NAME = "node-0";
        CassandraDaemonBlock block = CassandraDaemonBlock.create(
                EXPECTED_NAME, persistentOfferRequirementProvider, cassandraState, client);

        final OfferRequirement mockOfferReq = Mockito.mock(OfferRequirement.class);
        when(persistentOfferRequirementProvider.getNewOfferRequirement(Mockito.any())).thenReturn(
                Optional.of(mockOfferReq));

        Assert.assertTrue(block.start().isPresent());

        final CassandraDaemonTask task = taskFactory.create(EXPECTED_NAME,
                "abc",
                CassandraTaskExecutor.create("1234", EXPECTED_NAME, "cassandra-role", "cassandra-principal", config.getExecutorConfig()),
                config.getCassandraConfig());
        Protos.TaskInfo taskInfo = task.getTaskInfo();
        taskInfo = Protos.TaskInfo.newBuilder(taskInfo)
                .setSlaveId(Protos.SlaveID.newBuilder().setValue("1.2.3.4").build()).build();
        stateStore.storeTasks(Arrays.asList(taskInfo));

        final Protos.TaskStatus status = TestUtils.generateStatus(taskInfo.getTaskId(),
                Protos.TaskState.TASK_FINISHED);
        stateStore.storeStatus(status);
        when(persistentOfferRequirementProvider.getReplacementOfferRequirement(any())).thenReturn(
                Optional.of(mockOfferReq));
        Assert.assertTrue(block.start().isPresent());
    }

    @Test
    public void testStartTerminated() throws Exception {
        final String EXPECTED_NAME = "node-0";
        CassandraDaemonBlock block = CassandraDaemonBlock.create(EXPECTED_NAME,
                persistentOfferRequirementProvider, cassandraState, client);
        final OfferRequirement mockOfferReq = Mockito.mock(OfferRequirement.class);
        when(persistentOfferRequirementProvider.getNewOfferRequirement(Mockito.any())).thenReturn(
                Optional.of(mockOfferReq));

        Assert.assertTrue(block.start().isPresent());

        final CassandraDaemonTask task = taskFactory.create(EXPECTED_NAME,
                "abc",
                CassandraTaskExecutor.create("1234", EXPECTED_NAME, "cassandra-role", "cassandra-principal",
                        config.getExecutorConfig()),
                config.getCassandraConfig());
        Protos.TaskInfo taskInfo = task.getTaskInfo();
        taskInfo = Protos.TaskInfo.newBuilder(taskInfo)
                .setSlaveId(Protos.SlaveID.newBuilder().setValue("1.2.3.4").build()).build();
        stateStore.storeTasks(Arrays.asList(taskInfo));

        final Protos.TaskStatus status = TestUtils.generateStatus(taskInfo.getTaskId(),
                Protos.TaskState.TASK_FAILED);
        stateStore.storeStatus(status);
        when(persistentOfferRequirementProvider.getReplacementOfferRequirement(any())).thenReturn(
                Optional.of(mockOfferReq));
        OfferRequirement offerRequirement = block.start().get();
        Assert.assertEquals(mockOfferReq, offerRequirement);
    }

    @Test
    public void testStartLaunching() throws Exception {
        final String EXPECTED_NAME = "node-0";
        CassandraDaemonBlock block = CassandraDaemonBlock.create(
                EXPECTED_NAME, persistentOfferRequirementProvider, cassandraState, client);

        final OfferRequirement mockOfferReq = Mockito.mock(OfferRequirement.class);
        when(persistentOfferRequirementProvider.getNewOfferRequirement(Mockito.any())).thenReturn(
                Optional.of(mockOfferReq));

        Assert.assertTrue(block.start().isPresent());

        final CassandraDaemonTask task = taskFactory.create(EXPECTED_NAME,
                configurationManager.getTargetName().toString(),
                CassandraTaskExecutor.create("1234", EXPECTED_NAME, "cassandra-role", "cassandra-principal", config.getExecutorConfig()),
                config.getCassandraConfig());
        Protos.TaskInfo taskInfo = task.getTaskInfo();
        taskInfo = Protos.TaskInfo.newBuilder(taskInfo)
                .setSlaveId(Protos.SlaveID.newBuilder().setValue("1.2.3.4").build()).build();
        stateStore.storeTasks(Arrays.asList(taskInfo));

        final Protos.TaskStatus status = TestUtils.generateStatus(taskInfo.getTaskId(),
                Protos.TaskState.TASK_STAGING);
        stateStore.storeStatus(status);


        when(persistentOfferRequirementProvider.getReplacementOfferRequirement(any())).thenReturn(
                Optional.empty());
        Assert.assertTrue(!block.start().isPresent());

        when(persistentOfferRequirementProvider.getReplacementOfferRequirement(any())).thenReturn(
                Optional.of(mockOfferReq));
        Assert.assertEquals(mockOfferReq, block.start().get());
    }

    @Test
    public void testUpdateDataPresent() throws Exception {
        final String EXPECTED_NAME = "node-0";
        CassandraDaemonBlock block = CassandraDaemonBlock.create(
                EXPECTED_NAME, persistentOfferRequirementProvider, cassandraState, client);

        final OfferRequirement mockOfferReq = Mockito.mock(OfferRequirement.class);
        when(persistentOfferRequirementProvider.getNewOfferRequirement(Mockito.any())).thenReturn(
                Optional.of(mockOfferReq));

        Assert.assertTrue(block.start().isPresent());

        final CassandraDaemonTask task = taskFactory.create(EXPECTED_NAME,
                "abc",
                CassandraTaskExecutor.create("1234", EXPECTED_NAME, "cassandra-role", "cassandra-principal", config.getExecutorConfig()),
                config.getCassandraConfig());
        Protos.TaskInfo taskInfo = task.getTaskInfo();
        taskInfo = Protos.TaskInfo.newBuilder(taskInfo)
                .setSlaveId(Protos.SlaveID.newBuilder().setValue("1.2.3.4").build()).build();
        stateStore.storeTasks(Arrays.asList(taskInfo));
        block.updateOfferStatus(Optional.of(new ArrayList<>()));
        final Protos.TaskStatus status = TestUtils.generateStatus(taskInfo.getTaskId(),
                Protos.TaskState.TASK_RUNNING, CassandraMode.NORMAL);

        block.update(status);
    }

    @Test
    public void testUpdateDataNotPresent() throws Exception {
        final String EXPECTED_NAME = "node-0";
        CassandraDaemonBlock block = CassandraDaemonBlock.create(
                EXPECTED_NAME, persistentOfferRequirementProvider, cassandraState, client);

        final OfferRequirement mockOfferReq = Mockito.mock(OfferRequirement.class);
        when(persistentOfferRequirementProvider.getNewOfferRequirement(Mockito.any())).thenReturn(
                Optional.of(mockOfferReq));

        Assert.assertTrue(block.start().isPresent());

        final CassandraDaemonTask task = taskFactory.create(EXPECTED_NAME,
                "abc",
                CassandraTaskExecutor.create("1234", EXPECTED_NAME, "cassandra-role", "cassandra-principal", config.getExecutorConfig()),
                config.getCassandraConfig());
        Protos.TaskInfo taskInfo = task.getTaskInfo();
        taskInfo = Protos.TaskInfo.newBuilder(taskInfo)
                .setSlaveId(Protos.SlaveID.newBuilder().setValue("1.2.3.4").build()).build();
        stateStore.storeTasks(Arrays.asList(taskInfo));
        block.updateOfferStatus(Optional.of(new ArrayList<>()));
        final Protos.TaskStatus status = TestUtils.generateStatus(taskInfo.getTaskId(),
                Protos.TaskState.TASK_RUNNING);

        block.update(status);
    }
}
