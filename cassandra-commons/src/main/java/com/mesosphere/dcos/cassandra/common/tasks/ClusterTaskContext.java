/*
 * Copyright 2016 Mesosphere
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
package com.mesosphere.dcos.cassandra.common.tasks;

/**
 * Interface for the generic context object for ClusterTask execution (e.g
 * Backup, Restore, Cleanup, UpgradeSSTable, ... ). It is used to persist any necessary
 * state for the set of tasks that implement the cluster wie operation.
 */
public interface ClusterTaskContext {
}
