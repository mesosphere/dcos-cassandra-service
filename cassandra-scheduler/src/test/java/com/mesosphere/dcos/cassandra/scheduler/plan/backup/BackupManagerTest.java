package com.mesosphere.dcos.cassandra.scheduler.plan.backup;

import com.mesosphere.dcos.cassandra.common.serialization.SerializationException;
import com.mesosphere.dcos.cassandra.common.tasks.CassandraDaemonTask;
import com.mesosphere.dcos.cassandra.common.tasks.backup.BackupRestoreContext;
import com.mesosphere.dcos.cassandra.common.tasks.backup.BackupSnapshotTask;
import com.mesosphere.dcos.cassandra.common.tasks.backup.BackupUploadTask;
import com.mesosphere.dcos.cassandra.scheduler.offer.ClusterTaskOfferRequirementProvider;
import com.mesosphere.dcos.cassandra.scheduler.persistence.PersistenceException;
import com.mesosphere.dcos.cassandra.scheduler.tasks.CassandraTasks;

import org.apache.mesos.Protos;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.Protos.TaskStatus;
import org.apache.mesos.scheduler.plan.Block;
import org.apache.mesos.scheduler.plan.Phase;
import org.apache.mesos.state.StateStore;
import org.apache.mesos.state.StateStoreException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.Assert.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BackupManagerTest {
    private static final String SNAPSHOT_NODE_0 = "snapshot-node-0";
    private static final String UPLOAD_NODE_0 = "upload-node-0";
    private static final String NODE_0 = "node-0";

    @Mock private ClusterTaskOfferRequirementProvider mockProvider;
    @Mock private CassandraTasks mockCassandraTasks;
    @Mock private StateStore mockState;

    @Before
    public void beforeEach() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testInitialNoState() {
        when(mockState.fetchProperty(BackupManager.BACKUP_KEY)).thenThrow(
                new StateStoreException("no state found"));
        BackupManager manager = new BackupManager(mockCassandraTasks, mockProvider, mockState);
        assertFalse(manager.isComplete());
        assertFalse(manager.isInProgress());
        assertTrue(manager.getPhases().isEmpty());
    }

    @Test
    public void testInitialWithState() throws SerializationException {
        final BackupRestoreContext context =  BackupRestoreContext.create("", "", "", "", "", "", false);
        when(mockState.fetchProperty(BackupManager.BACKUP_KEY)).thenReturn(
                BackupRestoreContext.JSON_SERIALIZER.serialize(context));
        BackupManager manager = new BackupManager(mockCassandraTasks, mockProvider, mockState);
        assertTrue(manager.isComplete());
        assertFalse(manager.isInProgress());
        assertEquals(2, manager.getPhases().size());
    }

    @Test
    public void testStartCompleteStop() throws PersistenceException {
        when(mockState.fetchProperty(BackupManager.BACKUP_KEY)).thenThrow(
                new StateStoreException("no state found"));
        BackupManager manager = new BackupManager(mockCassandraTasks, mockProvider, mockState);

        final BackupRestoreContext context =  BackupRestoreContext.create("", "", "", "", "", "", false);
        final CassandraDaemonTask daemonTask = Mockito.mock(CassandraDaemonTask.class);
        Mockito.when(daemonTask.getState()).thenReturn(Protos.TaskState.TASK_RUNNING);
        final HashMap<String, CassandraDaemonTask> map = new HashMap<>();
        map.put(NODE_0, daemonTask);
        when(mockCassandraTasks.getDaemons()).thenReturn(map);
        when(mockCassandraTasks.get(SNAPSHOT_NODE_0)).thenReturn(Optional.of(daemonTask));
        when(mockCassandraTasks.get(UPLOAD_NODE_0)).thenReturn(Optional.of(daemonTask));

        manager.start(context);

        assertFalse(manager.isComplete());
        assertTrue(manager.isInProgress());
        assertEquals(2, manager.getPhases().size());

        Mockito.when(daemonTask.getState()).thenReturn(Protos.TaskState.TASK_FINISHED);
        // notify blocks to check for TASK_FINISHED:
        for (Phase phase : manager.getPhases()) {
            for (Block block : phase.getBlocks()) {
                block.update(TaskStatus.getDefaultInstance());
            }
        }

        assertTrue(manager.isComplete());
        assertFalse(manager.isInProgress());
        assertEquals(2, manager.getPhases().size());

        manager.stop();

        assertFalse(manager.isComplete());
        assertFalse(manager.isInProgress());
        assertTrue(manager.getPhases().isEmpty());
    }

    @Test
    public void testStartCompleteStart() throws PersistenceException {
        when(mockState.fetchProperty(BackupManager.BACKUP_KEY)).thenThrow(
                new StateStoreException("no state found"));
        BackupManager manager = new BackupManager(mockCassandraTasks, mockProvider, mockState);

        final BackupRestoreContext context =  BackupRestoreContext.create("", "", "", "", "", "", false);
        final CassandraDaemonTask daemonTask = Mockito.mock(CassandraDaemonTask.class);
        Mockito.when(daemonTask.getState()).thenReturn(Protos.TaskState.TASK_RUNNING);
        final HashMap<String, CassandraDaemonTask> map = new HashMap<>();
        map.put(NODE_0, daemonTask);
        when(mockCassandraTasks.getDaemons()).thenReturn(map);
        when(mockCassandraTasks.get(SNAPSHOT_NODE_0)).thenReturn(Optional.of(daemonTask));
        when(mockCassandraTasks.get(UPLOAD_NODE_0)).thenReturn(Optional.of(daemonTask));

        manager.start(context);

        assertFalse(manager.isComplete());
        assertTrue(manager.isInProgress());
        assertEquals(2, manager.getPhases().size());

        Mockito.when(daemonTask.getState()).thenReturn(Protos.TaskState.TASK_FINISHED);
        // notify blocks to check for TASK_FINISHED:
        for (Phase phase : manager.getPhases()) {
            for (Block block : phase.getBlocks()) {
                block.update(TaskStatus.getDefaultInstance());
            }
        }

        assertTrue(manager.isComplete());
        assertFalse(manager.isInProgress());
        assertEquals(2, manager.getPhases().size());

        Mockito.when(daemonTask.getState()).thenReturn(Protos.TaskState.TASK_RUNNING);
        Map<String, BackupUploadTask> previousUploadTasks = new HashMap<String, BackupUploadTask>();
        previousUploadTasks.put("hey", BackupUploadTask.parse(TaskInfo.getDefaultInstance()));
        Map<String, BackupSnapshotTask> previousBackupTasks =
                new HashMap<String, BackupSnapshotTask>();
        previousBackupTasks.put("hi", BackupSnapshotTask.parse(TaskInfo.getDefaultInstance()));
        when(mockCassandraTasks.getBackupUploadTasks()).thenReturn(previousUploadTasks);
        when(mockCassandraTasks.getBackupSnapshotTasks()).thenReturn(previousBackupTasks);

        manager.start(context);

        verify(mockCassandraTasks).remove("hi");
        verify(mockCassandraTasks).remove("hey");

        assertFalse(manager.isComplete());
        assertTrue(manager.isInProgress());
        assertEquals(2, manager.getPhases().size());
    }

    @Test
    public void testStartStop() {
        when(mockState.fetchProperty(BackupManager.BACKUP_KEY)).thenThrow(
                new StateStoreException("no state found"));
        BackupManager manager = new BackupManager(mockCassandraTasks, mockProvider, mockState);

        final BackupRestoreContext context =  BackupRestoreContext.create("", "", "", "", "", "", false);
        final CassandraDaemonTask daemonTask = Mockito.mock(CassandraDaemonTask.class);
        Mockito.when(daemonTask.getState()).thenReturn(Protos.TaskState.TASK_RUNNING);
        final HashMap<String, CassandraDaemonTask> map = new HashMap<>();
        map.put(NODE_0, daemonTask);
        when(mockCassandraTasks.getDaemons()).thenReturn(map);
        when(mockCassandraTasks.get(SNAPSHOT_NODE_0)).thenReturn(Optional.of(daemonTask));
        when(mockCassandraTasks.get(UPLOAD_NODE_0)).thenReturn(Optional.of(daemonTask));

        manager.start(context);

        assertFalse(manager.isComplete());
        assertTrue(manager.isInProgress());
        assertEquals(2, manager.getPhases().size());

        manager.stop();

        assertFalse(manager.isComplete());
        assertFalse(manager.isInProgress());
        assertTrue(manager.getPhases().isEmpty());
    }
}
