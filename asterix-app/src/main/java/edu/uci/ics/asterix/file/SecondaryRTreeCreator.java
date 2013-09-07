/*
 * Copyright 2009-2013 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.asterix.file;

import java.util.List;

import edu.uci.ics.asterix.common.api.ILocalResourceMetadata;
import edu.uci.ics.asterix.common.config.AsterixStorageProperties;
import edu.uci.ics.asterix.common.config.DatasetConfig.DatasetType;
import edu.uci.ics.asterix.common.config.IAsterixPropertiesProvider;
import edu.uci.ics.asterix.common.context.AsterixVirtualBufferCacheProvider;
import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.common.ioopcallbacks.LSMRTreeIOOperationCallbackFactory;
import edu.uci.ics.asterix.dataflow.data.nontagged.valueproviders.AqlPrimitiveValueProviderFactory;
import edu.uci.ics.asterix.external.adapter.factory.HDFSAdapterFactory;
import edu.uci.ics.asterix.external.data.operator.ExternalDataIndexingOperatorDescriptor;
import edu.uci.ics.asterix.external.util.ExternalIndexHashPartitionComputerFactory;
import edu.uci.ics.asterix.formats.nontagged.AqlBinaryComparatorFactoryProvider;
import edu.uci.ics.asterix.formats.nontagged.AqlSerializerDeserializerProvider;
import edu.uci.ics.asterix.formats.nontagged.AqlTypeTraitProvider;
import edu.uci.ics.asterix.metadata.declared.AqlMetadataProvider;
import edu.uci.ics.asterix.metadata.entities.ExternalDatasetDetails;
import edu.uci.ics.asterix.metadata.entities.Index;
import edu.uci.ics.asterix.metadata.utils.DatasetUtils;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.om.util.NonTaggedFormatUtil;
import edu.uci.ics.asterix.runtime.formats.NonTaggedDataFormat;
import edu.uci.ics.asterix.transaction.management.opcallbacks.SecondaryIndexOperationTrackerProvider;
import edu.uci.ics.asterix.transaction.management.resource.LSMRTreeLocalResourceMetadata;
import edu.uci.ics.asterix.transaction.management.resource.PersistentLocalResourceFactoryProvider;
import edu.uci.ics.asterix.transaction.management.service.transaction.AsterixRuntimeComponentsProvider;
import edu.uci.ics.asterix.translator.CompiledStatements.CompiledCreateIndexStatement;
import edu.uci.ics.hyracks.algebricks.common.constraints.AlgebricksPartitionConstraint;
import edu.uci.ics.hyracks.algebricks.common.constraints.AlgebricksPartitionConstraintHelper;
import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.common.utils.Pair;
import edu.uci.ics.hyracks.algebricks.core.jobgen.impl.ConnectorPolicyAssignmentPolicy;
import edu.uci.ics.hyracks.algebricks.core.rewriter.base.PhysicalOptimizationConfig;
import edu.uci.ics.hyracks.algebricks.runtime.operators.meta.AlgebricksMetaOperatorDescriptor;
import edu.uci.ics.hyracks.api.dataflow.IConnectorDescriptor;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryHashFunctionFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.api.dataflow.value.ITypeTraits;
import edu.uci.ics.hyracks.api.dataflow.value.RecordDescriptor;
import edu.uci.ics.hyracks.api.job.JobSpecification;
import edu.uci.ics.hyracks.dataflow.std.base.AbstractOperatorDescriptor;
import edu.uci.ics.hyracks.dataflow.std.connectors.MToNPartitioningConnectorDescriptor;
import edu.uci.ics.hyracks.dataflow.std.connectors.OneToOneConnectorDescriptor;
import edu.uci.ics.hyracks.storage.am.btree.dataflow.BTreeSearchOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.btree.impls.BTree;
import edu.uci.ics.hyracks.storage.am.common.api.IPrimitiveValueProviderFactory;
import edu.uci.ics.hyracks.storage.am.common.dataflow.TreeIndexBulkLoadOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.common.dataflow.TreeIndexCreateOperatorDescriptor;
import edu.uci.ics.hyracks.storage.am.common.impls.NoOpOperationCallbackFactory;
import edu.uci.ics.hyracks.storage.am.lsm.rtree.dataflow.LSMRTreeDataflowHelperFactory;
import edu.uci.ics.hyracks.storage.am.rtree.frames.RTreePolicyType;
import edu.uci.ics.hyracks.storage.common.file.ILocalResourceFactoryProvider;
import edu.uci.ics.hyracks.storage.common.file.LocalResource;

@SuppressWarnings("rawtypes")
public class SecondaryRTreeCreator extends SecondaryIndexCreator {

    protected IPrimitiveValueProviderFactory[] valueProviderFactories;
    protected int numNestedSecondaryKeyFields;
    protected ATypeTag keyType;

    protected SecondaryRTreeCreator(PhysicalOptimizationConfig physOptConf,
            IAsterixPropertiesProvider propertiesProvider) {
        super(physOptConf, propertiesProvider);
    }

    @Override
    public JobSpecification buildCreationJobSpec() throws AsterixException, AlgebricksException {
        JobSpecification spec = JobSpecificationUtils.createJobSpecification();

        AsterixStorageProperties storageProperties = propertiesProvider.getStorageProperties();
        //prepare a LocalResourceMetadata which will be stored in NC's local resource repository
        ILocalResourceMetadata localResourceMetadata = new LSMRTreeLocalResourceMetadata(
                secondaryRecDesc.getTypeTraits(), secondaryComparatorFactories, primaryComparatorFactories,
                valueProviderFactories, RTreePolicyType.RTREE, AqlMetadataProvider.proposeLinearizer(keyType,
                        secondaryComparatorFactories.length), dataset.getDatasetId());
        ILocalResourceFactoryProvider localResourceFactoryProvider = new PersistentLocalResourceFactoryProvider(
                localResourceMetadata, LocalResource.LSMRTreeResource);

        TreeIndexCreateOperatorDescriptor secondaryIndexCreateOp = new TreeIndexCreateOperatorDescriptor(spec,
                AsterixRuntimeComponentsProvider.RUNTIME_PROVIDER, AsterixRuntimeComponentsProvider.RUNTIME_PROVIDER,
                secondaryFileSplitProvider, secondaryRecDesc.getTypeTraits(), secondaryComparatorFactories, null,
                new LSMRTreeDataflowHelperFactory(valueProviderFactories, RTreePolicyType.RTREE,
                        primaryComparatorFactories, new AsterixVirtualBufferCacheProvider(dataset.getDatasetId()),
                        AsterixRuntimeComponentsProvider.RUNTIME_PROVIDER, new SecondaryIndexOperationTrackerProvider(
                                LSMRTreeIOOperationCallbackFactory.INSTANCE, dataset.getDatasetId()),
                        AsterixRuntimeComponentsProvider.RUNTIME_PROVIDER,
                        AsterixRuntimeComponentsProvider.RUNTIME_PROVIDER, AqlMetadataProvider.proposeLinearizer(
                                keyType, secondaryComparatorFactories.length), storageProperties
                                .getBloomFilterFalsePositiveRate()), localResourceFactoryProvider,
                NoOpOperationCallbackFactory.INSTANCE);
        AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, secondaryIndexCreateOp,
                secondaryPartitionConstraint);
        spec.addRoot(secondaryIndexCreateOp);
        spec.setConnectorPolicyAssignmentPolicy(new ConnectorPolicyAssignmentPolicy());
        return spec;
    }

    @Override
    protected void setSecondaryRecDescAndComparators(CompiledCreateIndexStatement createIndexStmt,
            AqlMetadataProvider metadata) throws AlgebricksException, AsterixException {
        List<String> secondaryKeyFields = createIndexStmt.getKeyFields();
        int numSecondaryKeys = secondaryKeyFields.size();
        if (numSecondaryKeys != 1) {
            throw new AsterixException(
                    "Cannot use "
                            + numSecondaryKeys
                            + " fields as a key for the R-tree index. There can be only one field as a key for the R-tree index.");
        }
        Pair<IAType, Boolean> spatialTypePair = Index.getNonNullableKeyFieldType(secondaryKeyFields.get(0), itemType);
        IAType spatialType = spatialTypePair.first;
        anySecondaryKeyIsNullable = spatialTypePair.second;
        if (spatialType == null) {
            throw new AsterixException("Could not find field " + secondaryKeyFields.get(0) + " in the schema.");
        }
        int numDimensions = NonTaggedFormatUtil.getNumDimensions(spatialType.getTypeTag());
        numNestedSecondaryKeyFields = numDimensions * 2;
        secondaryFieldAccessEvalFactories = metadata.getFormat().createMBRFactory(itemType, secondaryKeyFields.get(0),
                numPrimaryKeys, numDimensions);
        secondaryComparatorFactories = new IBinaryComparatorFactory[numNestedSecondaryKeyFields];
        valueProviderFactories = new IPrimitiveValueProviderFactory[numNestedSecondaryKeyFields];
        ISerializerDeserializer[] secondaryRecFields = new ISerializerDeserializer[numPrimaryKeys
                + numNestedSecondaryKeyFields];
        ITypeTraits[] secondaryTypeTraits = new ITypeTraits[numNestedSecondaryKeyFields + numPrimaryKeys];
        IAType nestedKeyType = NonTaggedFormatUtil.getNestedSpatialType(spatialType.getTypeTag());
        keyType = nestedKeyType.getTypeTag();
        for (int i = 0; i < numNestedSecondaryKeyFields; i++) {
            ISerializerDeserializer keySerde = AqlSerializerDeserializerProvider.INSTANCE
                    .getSerializerDeserializer(nestedKeyType);
            secondaryRecFields[i] = keySerde;
            secondaryComparatorFactories[i] = AqlBinaryComparatorFactoryProvider.INSTANCE.getBinaryComparatorFactory(
                    nestedKeyType, true);
            secondaryTypeTraits[i] = AqlTypeTraitProvider.INSTANCE.getTypeTrait(nestedKeyType);
            valueProviderFactories[i] = AqlPrimitiveValueProviderFactory.INSTANCE;
        }
        // Add serializers and comparators for primary index fields.
        for (int i = 0; i < numPrimaryKeys; i++) {
            secondaryRecFields[numNestedSecondaryKeyFields + i] = primaryRecDesc.getFields()[i];
            secondaryTypeTraits[numNestedSecondaryKeyFields + i] = primaryRecDesc.getTypeTraits()[i];
        }
        secondaryRecDesc = new RecordDescriptor(secondaryRecFields, secondaryTypeTraits);
    }

    @Override
	protected void setExternalSecondaryRecDescAndComparators(CompiledCreateIndexStatement createIndexStmt,
			AqlMetadataProvider metadataProvider) throws AlgebricksException, AsterixException {
		secondaryKeyFields = createIndexStmt.getKeyFields();
		if (numSecondaryKeys != 1) {
			throw new AsterixException(
					"Cannot use "
							+ numSecondaryKeys
							+ " fields as a key for the R-tree index. There can be only one field as a key for the R-tree index.");
		}
		Pair<IAType, Boolean> spatialTypePair = Index.getNonNullableKeyFieldType(secondaryKeyFields.get(0), itemType);
		IAType spatialType = spatialTypePair.first;
		anySecondaryKeyIsNullable = spatialTypePair.second;
		if (spatialType == null) {
			throw new AsterixException("Could not find field " + secondaryKeyFields.get(0) + " in the schema.");
		}
		int numDimensions = NonTaggedFormatUtil.getNumDimensions(spatialType.getTypeTag());
		numNestedSecondaryKeyFields = numDimensions * 2;
		secondaryFieldAccessEvalFactories = metadataProvider.getFormat().createMBRFactory(itemType, secondaryKeyFields.get(0),
				numPrimaryKeys, numDimensions);
		secondaryComparatorFactories = new IBinaryComparatorFactory[numNestedSecondaryKeyFields];
		valueProviderFactories = new IPrimitiveValueProviderFactory[numNestedSecondaryKeyFields];
		ISerializerDeserializer[] secondaryRecFields = new ISerializerDeserializer[numPrimaryKeys
		                                                                           + numNestedSecondaryKeyFields];
		ITypeTraits[] secondaryTypeTraits = new ITypeTraits[numNestedSecondaryKeyFields + numPrimaryKeys];
		IAType nestedKeyType = NonTaggedFormatUtil.getNestedSpatialType(spatialType.getTypeTag());
		keyType = nestedKeyType.getTypeTag();
		for (int i = 0; i < numNestedSecondaryKeyFields; i++) {
			ISerializerDeserializer keySerde = AqlSerializerDeserializerProvider.INSTANCE
					.getSerializerDeserializer(nestedKeyType);
			secondaryRecFields[i] = keySerde;
			secondaryComparatorFactories[i] = AqlBinaryComparatorFactoryProvider.INSTANCE.getBinaryComparatorFactory(
					nestedKeyType, true);
			secondaryTypeTraits[i] = AqlTypeTraitProvider.INSTANCE.getTypeTrait(nestedKeyType);
			valueProviderFactories[i] = AqlPrimitiveValueProviderFactory.INSTANCE;
		}

		// Add serializers and comparators for primary index fields.
		for (int i = 0; i < numPrimaryKeys; i++) {
			secondaryRecFields[numNestedSecondaryKeyFields + i] = primaryRecDesc.getFields()[i];
			secondaryTypeTraits[numNestedSecondaryKeyFields + i] = primaryRecDesc.getTypeTraits()[i];
		}
		secondaryRecDesc = new RecordDescriptor(secondaryRecFields, secondaryTypeTraits);
	}
    
    @Override
    public JobSpecification buildLoadingJobSpec() throws AsterixException, AlgebricksException {
        JobSpecification spec = JobSpecificationUtils.createJobSpecification();
        if (dataset.getDatasetType() == DatasetType.EXTERNAL) {
			Pair<ExternalDataIndexingOperatorDescriptor, AlgebricksPartitionConstraint> RIDScanOpAndConstraints;
			AlgebricksMetaOperatorDescriptor asterixAssignOp;
			try
			{
				//create external indexing scan operator
				RIDScanOpAndConstraints = createExternalIndexingOp(spec);
				//create assign operator
				asterixAssignOp = createExternalAssignOp(spec);
				AlgebricksPartitionConstraintHelper.setPartitionConstraintInJobSpec(spec, asterixAssignOp,
						RIDScanOpAndConstraints.second);
			}
			catch(Exception e)
			{
				throw new AsterixException("Failed to create external index scanning and loading job");
			}

			// If any of the secondary fields are nullable, then add a select op that filters nulls.
			AlgebricksMetaOperatorDescriptor selectOp = null;
			if (anySecondaryKeyIsNullable) {
				selectOp = createFilterNullsSelectOp(spec, numSecondaryKeys);
			}

			// Create secondary RTree bulk load op.
			AsterixStorageProperties storageProperties = propertiesProvider.getStorageProperties();
			TreeIndexBulkLoadOperatorDescriptor secondaryBulkLoadOp = createTreeIndexBulkLoadOp(
					spec,
					numNestedSecondaryKeyFields,
					new LSMRTreeDataflowHelperFactory(valueProviderFactories, RTreePolicyType.RTREE,
	                        primaryComparatorFactories, new AsterixVirtualBufferCacheProvider(dataset.getDatasetId()),
	                        AsterixRuntimeComponentsProvider.RUNTIME_PROVIDER, new SecondaryIndexOperationTrackerProvider(
	                                LSMRTreeIOOperationCallbackFactory.INSTANCE, dataset.getDatasetId()),
	                        AsterixRuntimeComponentsProvider.RUNTIME_PROVIDER,
	                        AsterixRuntimeComponentsProvider.RUNTIME_PROVIDER, AqlMetadataProvider.proposeLinearizer(
	                                keyType, secondaryComparatorFactories.length), storageProperties
	                                .getBloomFilterFalsePositiveRate()), BTree.DEFAULT_FILL_FACTOR);
			// Connect the operators.
			// Create a hash partitioning connector
			ExternalDatasetDetails edsd = (ExternalDatasetDetails)dataset.getDatasetDetails();
			IBinaryHashFunctionFactory[] hashFactories = null;
			if(edsd.getProperties().get(HDFSAdapterFactory.KEY_INPUT_FORMAT).trim().equals(HDFSAdapterFactory.INPUT_FORMAT_RC))
			{
				hashFactories = DatasetUtils.computeExternalDataKeysBinaryHashFunFactories(dataset, NonTaggedDataFormat.INSTANCE.getBinaryHashFunctionFactoryProvider());
			}
			else
			{
				hashFactories = DatasetUtils.computeExternalDataKeysBinaryHashFunFactories(dataset, NonTaggedDataFormat.INSTANCE.getBinaryHashFunctionFactoryProvider());
			}	 
			//select partitioning keys (always the first 2 after secondary keys)
			int[] keys = new int[2];
			keys[0] = numSecondaryKeys;
			keys[1] = numSecondaryKeys + 1;

			IConnectorDescriptor hashConn = new MToNPartitioningConnectorDescriptor(spec,
					new ExternalIndexHashPartitionComputerFactory(keys, hashFactories));
			spec.connect(new OneToOneConnectorDescriptor(spec), RIDScanOpAndConstraints.first, 0, asterixAssignOp, 0);
			if (anySecondaryKeyIsNullable) {
				spec.connect(new OneToOneConnectorDescriptor(spec), asterixAssignOp, 0, selectOp, 0);
				spec.connect(hashConn, selectOp, 0, secondaryBulkLoadOp, 0);
			} else {
				spec.connect(hashConn, asterixAssignOp, 0, secondaryBulkLoadOp, 0);
			}
			spec.addRoot(secondaryBulkLoadOp);
			spec.setConnectorPolicyAssignmentPolicy(new ConnectorPolicyAssignmentPolicy());
			return spec;
		}
		else
		{

        // Create dummy key provider for feeding the primary index scan. 
        AbstractOperatorDescriptor keyProviderOp = createDummyKeyProviderOp(spec);

        // Create primary index scan op.
        BTreeSearchOperatorDescriptor primaryScanOp = createPrimaryIndexScanOp(spec);

        // Assign op.
        AlgebricksMetaOperatorDescriptor asterixAssignOp = createAssignOp(spec, primaryScanOp,
                numNestedSecondaryKeyFields);

        // If any of the secondary fields are nullable, then add a select op that filters nulls.
        AlgebricksMetaOperatorDescriptor selectOp = null;
        if (anySecondaryKeyIsNullable) {
            selectOp = createFilterNullsSelectOp(spec, numNestedSecondaryKeyFields);
        }

        AsterixStorageProperties storageProperties = propertiesProvider.getStorageProperties();
        // Create secondary RTree bulk load op.
        TreeIndexBulkLoadOperatorDescriptor secondaryBulkLoadOp = createTreeIndexBulkLoadOp(
                spec,
                numNestedSecondaryKeyFields,
                new LSMRTreeDataflowHelperFactory(valueProviderFactories, RTreePolicyType.RTREE,
                        primaryComparatorFactories, new AsterixVirtualBufferCacheProvider(dataset.getDatasetId()),
                        AsterixRuntimeComponentsProvider.RUNTIME_PROVIDER, new SecondaryIndexOperationTrackerProvider(
                                LSMRTreeIOOperationCallbackFactory.INSTANCE, dataset.getDatasetId()),
                        AsterixRuntimeComponentsProvider.RUNTIME_PROVIDER,
                        AsterixRuntimeComponentsProvider.RUNTIME_PROVIDER, AqlMetadataProvider.proposeLinearizer(
                                keyType, secondaryComparatorFactories.length), storageProperties
                                .getBloomFilterFalsePositiveRate()), BTree.DEFAULT_FILL_FACTOR);

        // Connect the operators.
        spec.connect(new OneToOneConnectorDescriptor(spec), keyProviderOp, 0, primaryScanOp, 0);
        spec.connect(new OneToOneConnectorDescriptor(spec), primaryScanOp, 0, asterixAssignOp, 0);
        if (anySecondaryKeyIsNullable) {
            spec.connect(new OneToOneConnectorDescriptor(spec), asterixAssignOp, 0, selectOp, 0);
            spec.connect(new OneToOneConnectorDescriptor(spec), selectOp, 0, secondaryBulkLoadOp, 0);
        } else {
            spec.connect(new OneToOneConnectorDescriptor(spec), asterixAssignOp, 0, secondaryBulkLoadOp, 0);
        }
        spec.addRoot(secondaryBulkLoadOp);
        spec.setConnectorPolicyAssignmentPolicy(new ConnectorPolicyAssignmentPolicy());
        return spec;
		}
    }
}
