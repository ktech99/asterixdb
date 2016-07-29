/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.transaction.management.resource;

import org.apache.asterix.common.api.ILocalResourceMetadata;
import org.apache.hyracks.storage.common.file.ILocalResourceFactory;
import org.apache.hyracks.storage.common.file.LocalResource;

public class PersistentLocalResourceFactory implements ILocalResourceFactory {

    private final ILocalResourceMetadata localResourceMetadata;
    private final int resourceType;

    public PersistentLocalResourceFactory(ILocalResourceMetadata localResourceMetadata, int resourceType) {
        this.localResourceMetadata = localResourceMetadata;
        this.resourceType = resourceType;
    }

    @Override
    public LocalResource createLocalResource(long resourceId, String resourceName, int partition, int storageVersion,
            String resourcePath) {
        return new LocalResource(resourceId, resourceName, partition, resourcePath, resourceType, storageVersion,
                localResourceMetadata);
    }
}
