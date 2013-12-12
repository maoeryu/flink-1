/***********************************************************************************************************************
 *
 * Copyright (C) 2010-2013 by the Stratosphere project (http://stratosphere.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 **********************************************************************************************************************/

package eu.stratosphere.pact.test.iterative.nephele;

import eu.stratosphere.nephele.configuration.Configuration;
import eu.stratosphere.nephele.io.DistributionPattern;
import eu.stratosphere.nephele.io.channels.ChannelType;
import eu.stratosphere.nephele.jobgraph.*;
import eu.stratosphere.pact.common.io.FileOutputFormat;
import eu.stratosphere.pact.common.io.RecordInputFormat;
import eu.stratosphere.pact.common.io.RecordOutputFormat;
import eu.stratosphere.pact.common.stubs.Collector;
import eu.stratosphere.pact.common.stubs.MapStub;
import eu.stratosphere.pact.common.stubs.aggregators.LongSumAggregator;
import eu.stratosphere.pact.common.type.PactRecord;
import eu.stratosphere.pact.common.type.base.PactLong;
import eu.stratosphere.pact.example.connectedcomponents.WorksetConnectedComponents.MinimumComponentIDReduce;
import eu.stratosphere.pact.example.connectedcomponents.WorksetConnectedComponents.NeighborWithComponentIDJoin;
import eu.stratosphere.pact.example.connectedcomponents.WorksetConnectedComponents.UpdateComponentIdMatch;
import eu.stratosphere.pact.generic.contract.UserCodeClassWrapper;
import eu.stratosphere.pact.generic.types.TypeComparatorFactory;
import eu.stratosphere.pact.generic.types.TypePairComparatorFactory;
import eu.stratosphere.pact.generic.types.TypeSerializerFactory;
import eu.stratosphere.pact.runtime.iterative.convergence.WorksetEmptyConvergenceCriterion;
import eu.stratosphere.pact.runtime.iterative.task.IterationHeadPactTask;
import eu.stratosphere.pact.runtime.iterative.task.IterationIntermediatePactTask;
import eu.stratosphere.pact.runtime.iterative.task.IterationTailPactTask;
import eu.stratosphere.pact.runtime.plugable.pactrecord.PactRecordComparatorFactory;
import eu.stratosphere.pact.runtime.plugable.pactrecord.PactRecordPairComparatorFactory;
import eu.stratosphere.pact.runtime.plugable.pactrecord.PactRecordSerializerFactory;
import eu.stratosphere.pact.runtime.shipping.ShipStrategyType;
import eu.stratosphere.pact.runtime.task.BuildSecondCachedMatchDriver;
import eu.stratosphere.pact.runtime.task.DriverStrategy;
import eu.stratosphere.pact.runtime.task.JoinWithSolutionSetMatchDriver.SolutionSetSecondJoinDriver;
import eu.stratosphere.pact.runtime.task.MapDriver;
import eu.stratosphere.pact.runtime.task.ReduceDriver;
import eu.stratosphere.pact.runtime.task.chaining.ChainedMapDriver;
import eu.stratosphere.pact.runtime.task.util.LocalStrategy;
import eu.stratosphere.pact.runtime.task.util.TaskConfig;
import eu.stratosphere.pact.test.util.TestBase2;
import org.junit.Assert;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Collection;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Tests the various variants of iteration state updates for workset iterations:
 * - unified solution set and workset tail update
 * - separate solution set and workset tail updates
 * - intermediate workset update and solution set tail
 * - intermediate solution set update and workset tail
 */
@RunWith(Parameterized.class)
public class ConnectedComponentsNepheleITCase extends TestBase2 {

    private static final long SEED = 0xBADC0FFEEBEEFL;
    private static final int NUM_VERTICES = 1000;
    private static final int NUM_EDGES = 10000;
    private static final int ITERATION_ID = 1;
    private static final long MEM_PER_CONSUMER = 10;
    
    protected String verticesPath;
    protected String edgesPath;
    protected String resultPath;

    public ConnectedComponentsNepheleITCase(Configuration config) {
        super(config);
    }

    @Parameters
    public static Collection<Object[]> getConfigurations() {
        Configuration config1 = new Configuration();
        config1.setInteger("testcase", 1);
        
        Configuration config2 = new Configuration();
        config2.setInteger("testcase", 2);
        
        Configuration config3 = new Configuration();
        config3.setInteger("testcase", 3);
        
        Configuration config4 = new Configuration();
        config4.setInteger("testcase", 4);
        
        return toParameterList(config1, config2, config3, config4);
    }

    public static final String getEnumeratingVertices(int num) {
        if (num < 1 || num > 1000000)
            throw new IllegalArgumentException();

        StringBuilder bld = new StringBuilder(3 * num);
        for (int i = 1; i <= num; i++) {
            bld.append(i);
            bld.append('\n');
        }
        return bld.toString();
    }

    /**
     * Creates random edges such that even numbered vertices are connected with even numbered vertices
     * and odd numbered vertices only with other odd numbered ones.
     *
     * @param numEdges
     * @param numVertices
     * @param seed
     * @return
     */
    public static final String getRandomOddEvenEdges(int numEdges, int numVertices, long seed) {
        if (numVertices < 2 || numVertices > 1000000 || numEdges < numVertices || numEdges > 1000000)
            throw new IllegalArgumentException();

        StringBuilder bld = new StringBuilder(5 * numEdges);

        // first create the linear edge sequence even -> even and odd -> odd to make sure they are
        // all in the same component
        for (int i = 3; i <= numVertices; i++) {
            bld.append(i - 2).append(' ').append(i).append('\n');
        }

        numEdges -= numVertices - 2;
        Random r = new Random(seed);

        for (int i = 1; i <= numEdges; i++) {
            int evenOdd = r.nextBoolean() ? 1 : 0;

            int source = r.nextInt(numVertices) + 1;
            if (source % 2 != evenOdd) {
                source--;
                if (source < 1) {
                    source = 2;
                }
            }

            int target = r.nextInt(numVertices) + 1;
            if (target % 2 != evenOdd) {
                target--;
                if (target < 1) {
                    target = 2;
                }
            }

            bld.append(source).append(' ').append(target).append('\n');
        }
        return bld.toString();
    }

    public static void checkOddEvenResult(BufferedReader result) throws IOException {
        Pattern split = Pattern.compile(" ");
        String line;
        while ((line = result.readLine()) != null) {
            String[] res = split.split(line);
            Assert.assertEquals("Malfored result: Wrong number of tokens in line.", 2, res.length);
            try {
                int vertex = Integer.parseInt(res[0]);
                int component = Integer.parseInt(res[1]);

                int should = vertex % 2;
                if (should == 0) {
                    should = 2;
                }
                Assert.assertEquals("Vertex is in wrong component.", should, component);
            } catch (NumberFormatException e) {
                Assert.fail("Malformed result.");
            }
        }
    }

    @Override
    protected void preSubmit() throws Exception {
        verticesPath = createTempFile("vertices.txt", getEnumeratingVertices(NUM_VERTICES));
        edgesPath = createTempFile("edges.txt", getRandomOddEvenEdges(NUM_EDGES, NUM_VERTICES, SEED));
        resultPath = getTempFilePath("results");
    }

    @Override
    protected JobGraph getJobGraph() throws Exception {
        int dop = 4;
        int maxIterations = 100;

        int type = config.getInteger("testcase", 0);
        switch (type) {
        case 1:
            return createJobGraphUnifiedTails(verticesPath, edgesPath, resultPath, dop, maxIterations);
        case 2:
            return createJobGraphSeparateTails(verticesPath, edgesPath, resultPath, dop, maxIterations);
        case 3:
            return createJobGraphIntermediateWorksetUpdateAndSolutionSetTail(verticesPath, edgesPath, resultPath, dop, maxIterations);
        case 4:
            return createJobGraphSolutionSetUpdateAndWorksetTail(verticesPath, edgesPath, resultPath, dop, maxIterations);
        default:
            throw new RuntimeException("Broken test configuration");
        }
    }

    @Override
    protected void postSubmit() throws Exception {
        for (BufferedReader reader : getResultReader(resultPath)) {
            checkOddEvenResult(reader);
        }
    }

    public static final class IdDuplicator extends MapStub {

        @Override
        public void map(PactRecord record, Collector<PactRecord> out) throws Exception {
            record.setField(1, record.getField(0, PactLong.class));
            out.collect(record);
        }

    }

    // -----------------------------------------------------------------------------------------------------------------
    // Invariant vertices across all variants
    // -----------------------------------------------------------------------------------------------------------------

    private static JobInputVertex createVerticesInput(JobGraph jobGraph, String verticesPath, int numSubTasks,
                                                      TypeSerializerFactory<?> serializer,
                                                      TypeComparatorFactory<?> comparator) {
        @SuppressWarnings("unchecked")
        RecordInputFormat verticesInFormat = new RecordInputFormat(' ', PactLong.class);
        JobInputVertex verticesInput = JobGraphUtils.createInput(verticesInFormat, verticesPath, "VerticesInput",
                jobGraph, numSubTasks, numSubTasks);
        TaskConfig verticesInputConfig = new TaskConfig(verticesInput.getConfiguration());
        {
            verticesInputConfig.addOutputShipStrategy(ShipStrategyType.FORWARD);
            verticesInputConfig.setOutputSerializer(serializer);

            // chained mapper that duplicates the id
            TaskConfig chainedMapperConfig = new TaskConfig(new Configuration());
            chainedMapperConfig.setStubWrapper(new UserCodeClassWrapper<IdDuplicator>(IdDuplicator.class));
            chainedMapperConfig.setDriverStrategy(DriverStrategy.MAP);
            chainedMapperConfig.setInputLocalStrategy(0, LocalStrategy.NONE);
            chainedMapperConfig.setInputSerializer(serializer, 0);

            chainedMapperConfig.setOutputSerializer(serializer);
            chainedMapperConfig.addOutputShipStrategy(ShipStrategyType.PARTITION_HASH);
            chainedMapperConfig.addOutputShipStrategy(ShipStrategyType.PARTITION_HASH);
            chainedMapperConfig.setOutputComparator(comparator, 0);
            chainedMapperConfig.setOutputComparator(comparator, 1);

            verticesInputConfig.addChainedTask(ChainedMapDriver.class, chainedMapperConfig, "ID Duplicator");
        }

        return verticesInput;
    }

    private static JobInputVertex createEdgesInput(JobGraph jobGraph, String edgesPath, int numSubTasks,
                                                   TypeSerializerFactory<?> serializer,
                                                   TypeComparatorFactory<?> comparator) {
        // edges
        @SuppressWarnings("unchecked")
        RecordInputFormat edgesInFormat = new RecordInputFormat(' ', PactLong.class, PactLong.class);
        JobInputVertex edgesInput = JobGraphUtils.createInput(edgesInFormat, edgesPath, "EdgesInput", jobGraph,
                numSubTasks, numSubTasks);
        TaskConfig edgesInputConfig = new TaskConfig(edgesInput.getConfiguration());
        {
            edgesInputConfig.setOutputSerializer(serializer);
            edgesInputConfig.addOutputShipStrategy(ShipStrategyType.PARTITION_HASH);
            edgesInputConfig.setOutputComparator(comparator, 0);
        }

        return edgesInput;
    }

    private static JobTaskVertex createIterationHead(JobGraph jobGraph, int numSubTasks,
                                                     TypeSerializerFactory<?> serializer,
                                                     TypeComparatorFactory<?> comparator,
                                                     TypePairComparatorFactory<?, ?> pairComparator) {

        JobTaskVertex head = JobGraphUtils.createTask(IterationHeadPactTask.class, "Join With Edges (Iteration Head)",
                jobGraph, numSubTasks, numSubTasks);
        TaskConfig headConfig = new TaskConfig(head.getConfiguration());
        {
            headConfig.setIterationId(ITERATION_ID);

            // initial input / workset
            headConfig.addInputToGroup(0);
            headConfig.setInputSerializer(serializer, 0);
            headConfig.setInputComparator(comparator, 0);
            headConfig.setInputLocalStrategy(0, LocalStrategy.NONE);
            headConfig.setIterationHeadPartialSolutionOrWorksetInputIndex(0);

            // regular plan input (second input to the join)
            headConfig.addInputToGroup(1);
            headConfig.setInputSerializer(serializer, 1);
            headConfig.setInputComparator(comparator, 1);
            headConfig.setInputLocalStrategy(1, LocalStrategy.NONE);
            headConfig.setInputCached(1, true);
            headConfig.setInputMaterializationMemory(1, MEM_PER_CONSUMER * JobGraphUtils.MEGABYTE);

            // initial solution set input
            headConfig.addInputToGroup(2);
            headConfig.setInputSerializer(serializer, 2);
            headConfig.setInputComparator(comparator, 2);
            headConfig.setInputLocalStrategy(2, LocalStrategy.NONE);
            headConfig.setIterationHeadSolutionSetInputIndex(2);

            headConfig.setSolutionSetSerializer(serializer);
            headConfig.setSolutionSetComparator(comparator);
            headConfig.setSolutionSetProberSerializer(serializer);
            headConfig.setSolutionSetProberComparator(comparator);
            headConfig.setSolutionSetPairComparator(pairComparator);

            // back channel / iterations
            headConfig.setIsWorksetIteration();
            headConfig.setBackChannelMemory(MEM_PER_CONSUMER * JobGraphUtils.MEGABYTE);
            headConfig.setSolutionSetMemory(MEM_PER_CONSUMER * JobGraphUtils.MEGABYTE);

            // output into iteration
            headConfig.setOutputSerializer(serializer);
            headConfig.addOutputShipStrategy(ShipStrategyType.PARTITION_HASH);
            headConfig.setOutputComparator(comparator, 0);

            // final output
            TaskConfig headFinalOutConfig = new TaskConfig(new Configuration());
            headFinalOutConfig.setOutputSerializer(serializer);
            headFinalOutConfig.addOutputShipStrategy(ShipStrategyType.FORWARD);
            headConfig.setIterationHeadFinalOutputConfig(headFinalOutConfig);

            // the sync
            headConfig.setIterationHeadIndexOfSyncOutput(2);

            // the driver
            headConfig.setDriver(BuildSecondCachedMatchDriver.class);
            headConfig.setDriverStrategy(DriverStrategy.HYBRIDHASH_BUILD_SECOND);
            headConfig.setStubWrapper(
                    new UserCodeClassWrapper<NeighborWithComponentIDJoin>(NeighborWithComponentIDJoin.class));
            headConfig.setDriverComparator(comparator, 0);
            headConfig.setDriverComparator(comparator, 1);
            headConfig.setDriverPairComparator(pairComparator);
            headConfig.setMemoryDriver(MEM_PER_CONSUMER * JobGraphUtils.MEGABYTE);

            headConfig.addIterationAggregator(
                    WorksetEmptyConvergenceCriterion.AGGREGATOR_NAME, LongSumAggregator.class);
        }

        return head;
    }

    private static JobTaskVertex createIterationIntermediate(JobGraph jobGraph, int numSubTasks,
                                                             TypeSerializerFactory<?> serializer,
                                                             TypeComparatorFactory<?> comparator) {

        // --------------- the intermediate (reduce to min id) ---------------
        JobTaskVertex intermediate = JobGraphUtils.createTask(IterationIntermediatePactTask.class,
                "Find Min Component-ID", jobGraph, numSubTasks, numSubTasks);
        TaskConfig intermediateConfig = new TaskConfig(intermediate.getConfiguration());
        {
            intermediateConfig.setIterationId(ITERATION_ID);

            intermediateConfig.addInputToGroup(0);
            intermediateConfig.setInputSerializer(serializer, 0);
            intermediateConfig.setInputComparator(comparator, 0);
            intermediateConfig.setInputLocalStrategy(0, LocalStrategy.SORT);
            intermediateConfig.setMemoryInput(0, MEM_PER_CONSUMER * JobGraphUtils.MEGABYTE);
            intermediateConfig.setFilehandlesInput(0, 64);
            intermediateConfig.setSpillingThresholdInput(0, 0.85f);

            intermediateConfig.setOutputSerializer(serializer);
            intermediateConfig.addOutputShipStrategy(ShipStrategyType.FORWARD);

            intermediateConfig.setDriver(ReduceDriver.class);
            intermediateConfig.setDriverStrategy(DriverStrategy.SORTED_GROUP);
            intermediateConfig.setDriverComparator(comparator, 0);
            intermediateConfig.setStubWrapper(
                    new UserCodeClassWrapper<MinimumComponentIDReduce>(MinimumComponentIDReduce.class));
        }

        return intermediate;
    }

    private static JobOutputVertex createOutput(JobGraph jobGraph, String resultPath, int numSubTasks,
                                                TypeSerializerFactory<?> serializer) {
        JobOutputVertex output = JobGraphUtils.createFileOutput(jobGraph, "Final Output", numSubTasks, numSubTasks);
        TaskConfig outputConfig = new TaskConfig(output.getConfiguration());
        {

            outputConfig.addInputToGroup(0);
            outputConfig.setInputSerializer(serializer, 0);

            outputConfig.setStubWrapper(new UserCodeClassWrapper<RecordOutputFormat>(RecordOutputFormat.class));
            outputConfig.setStubParameter(FileOutputFormat.FILE_PARAMETER_KEY, resultPath);

            Configuration outputUserConfig = outputConfig.getStubParameters();
            outputUserConfig.setString(RecordOutputFormat.RECORD_DELIMITER_PARAMETER, "\n");
            outputUserConfig.setString(RecordOutputFormat.FIELD_DELIMITER_PARAMETER, " ");
            outputUserConfig.setClass(RecordOutputFormat.FIELD_TYPE_PARAMETER_PREFIX + 0, PactLong.class);
            outputUserConfig.setInteger(RecordOutputFormat.RECORD_POSITION_PARAMETER_PREFIX + 0, 0);
            outputUserConfig.setClass(RecordOutputFormat.FIELD_TYPE_PARAMETER_PREFIX + 1, PactLong.class);
            outputUserConfig.setInteger(RecordOutputFormat.RECORD_POSITION_PARAMETER_PREFIX + 1, 1);
            outputUserConfig.setInteger(RecordOutputFormat.NUM_FIELDS_PARAMETER, 2);
        }

        return output;
    }

    private static JobOutputVertex createFakeTail(JobGraph jobGraph, int numSubTasks) {
        JobOutputVertex fakeTailOutput =
                JobGraphUtils.createFakeOutput(jobGraph, "FakeTailOutput", numSubTasks, numSubTasks);
        return fakeTailOutput;
    }

    private static JobOutputVertex createSync(JobGraph jobGraph, int numSubTasks, int maxIterations) {
        JobOutputVertex sync = JobGraphUtils.createSync(jobGraph, numSubTasks);
        TaskConfig syncConfig = new TaskConfig(sync.getConfiguration());
        syncConfig.setNumberOfIterations(maxIterations);
        syncConfig.setIterationId(ITERATION_ID);
        syncConfig.addIterationAggregator(WorksetEmptyConvergenceCriterion.AGGREGATOR_NAME,
                LongSumAggregator.class);
        syncConfig.setConvergenceCriterion(WorksetEmptyConvergenceCriterion.AGGREGATOR_NAME,
                WorksetEmptyConvergenceCriterion.class);

        return sync;
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Unified solution set and workset tail update
    // -----------------------------------------------------------------------------------------------------------------

    public JobGraph createJobGraphUnifiedTails(
            String verticesPath, String edgesPath, String resultPath, int numSubTasks, int maxIterations)
            throws JobGraphDefinitionException {
    	
    	numSubTasks = 1;

        // -- init -------------------------------------------------------------------------------------------------
        final TypeSerializerFactory<?> serializer = PactRecordSerializerFactory.get();
        @SuppressWarnings("unchecked")
        final TypeComparatorFactory<?> comparator =
                new PactRecordComparatorFactory(new int[]{0}, new Class[]{PactLong.class}, new boolean[]{true});
        final TypePairComparatorFactory<?, ?> pairComparator = PactRecordPairComparatorFactory.get();

        JobGraph jobGraph = new JobGraph("Connected Components (Unified Tails)");

        // -- invariant vertices -----------------------------------------------------------------------------------
        JobInputVertex vertices = createVerticesInput(jobGraph, verticesPath, numSubTasks, serializer, comparator);
        JobInputVertex edges = createEdgesInput(jobGraph, edgesPath, numSubTasks, serializer, comparator);
        JobTaskVertex head = createIterationHead(jobGraph, numSubTasks, serializer, comparator, pairComparator);

        JobTaskVertex intermediate = createIterationIntermediate(jobGraph, numSubTasks, serializer, comparator);
        TaskConfig intermediateConfig = new TaskConfig(intermediate.getConfiguration());

        JobOutputVertex output = createOutput(jobGraph, resultPath, numSubTasks, serializer);
        JobOutputVertex fakeTail = createFakeTail(jobGraph, numSubTasks);
        JobOutputVertex sync = createSync(jobGraph, numSubTasks, maxIterations);

        // --------------- the tail (solution set join) ---------------
        JobTaskVertex tail = JobGraphUtils.createTask(IterationTailPactTask.class, "IterationTail", jobGraph,
                numSubTasks, numSubTasks);
        TaskConfig tailConfig = new TaskConfig(tail.getConfiguration());
        {
            tailConfig.setIterationId(ITERATION_ID);
            
            tailConfig.setIsWorksetIteration();
            tailConfig.setIsWorksetUpdate();
            
            tailConfig.setIsSolutionSetUpdate();
            tailConfig.setIsSolutionSetUpdateWithoutReprobe();

            // inputs and driver
            tailConfig.addInputToGroup(0);
            tailConfig.setInputSerializer(serializer, 0);

            // output
            tailConfig.addOutputShipStrategy(ShipStrategyType.FORWARD);
            tailConfig.setOutputSerializer(serializer);

            // the driver
            tailConfig.setDriver(SolutionSetSecondJoinDriver.class);
            tailConfig.setDriverStrategy(DriverStrategy.HYBRIDHASH_BUILD_SECOND);
            tailConfig.setStubWrapper(new UserCodeClassWrapper<UpdateComponentIdMatch>(UpdateComponentIdMatch.class));
            tailConfig.setSolutionSetSerializer(serializer);
        }

        // -- edges ------------------------------------------------------------------------------------------------
        JobGraphUtils.connect(vertices, head, ChannelType.NETWORK, DistributionPattern.BIPARTITE);
        JobGraphUtils.connect(edges, head, ChannelType.NETWORK, DistributionPattern.BIPARTITE);
        JobGraphUtils.connect(vertices, head, ChannelType.NETWORK, DistributionPattern.BIPARTITE);

        JobGraphUtils.connect(head, intermediate, ChannelType.NETWORK, DistributionPattern.BIPARTITE);
        intermediateConfig.setGateIterativeWithNumberOfEventsUntilInterrupt(0, numSubTasks);

        JobGraphUtils.connect(intermediate, tail, ChannelType.INMEMORY, DistributionPattern.POINTWISE);
        tailConfig.setGateIterativeWithNumberOfEventsUntilInterrupt(0, 1);

        JobGraphUtils.connect(head, output, ChannelType.INMEMORY, DistributionPattern.POINTWISE);
        JobGraphUtils.connect(tail, fakeTail, ChannelType.INMEMORY, DistributionPattern.POINTWISE);

        JobGraphUtils.connect(head, sync, ChannelType.NETWORK, DistributionPattern.POINTWISE);

        vertices.setVertexToShareInstancesWith(head);
        edges.setVertexToShareInstancesWith(head);

        intermediate.setVertexToShareInstancesWith(head);
        tail.setVertexToShareInstancesWith(head);

        output.setVertexToShareInstancesWith(head);
        sync.setVertexToShareInstancesWith(head);
        fakeTail.setVertexToShareInstancesWith(tail);

        return jobGraph;
    }


    public JobGraph createJobGraphSeparateTails(
            String verticesPath, String edgesPath, String resultPath, int numSubTasks, int maxIterations)
            throws JobGraphDefinitionException {
        // -- init -------------------------------------------------------------------------------------------------
        final TypeSerializerFactory<?> serializer = PactRecordSerializerFactory.get();
        @SuppressWarnings("unchecked")
        final TypeComparatorFactory<?> comparator =
                new PactRecordComparatorFactory(new int[]{0}, new Class[]{PactLong.class}, new boolean[]{true});
        final TypePairComparatorFactory<?, ?> pairComparator = PactRecordPairComparatorFactory.get();

        JobGraph jobGraph = new JobGraph("Connected Components (Unified Tails)");

        // input
        JobInputVertex vertices = createVerticesInput(jobGraph, verticesPath, numSubTasks, serializer, comparator);
        JobInputVertex edges = createEdgesInput(jobGraph, edgesPath, numSubTasks, serializer, comparator);

        // head
        JobTaskVertex head = createIterationHead(jobGraph, numSubTasks, serializer, comparator, pairComparator);
        TaskConfig headConfig = new TaskConfig(head.getConfiguration());
        headConfig.setWaitForSolutionSetUpdate();

        // intermediate
        JobTaskVertex intermediate = createIterationIntermediate(jobGraph, numSubTasks, serializer, comparator);
        TaskConfig intermediateConfig = new TaskConfig(intermediate.getConfiguration());

        // output and auxiliaries
        JobOutputVertex output = createOutput(jobGraph, resultPath, numSubTasks, serializer);
        JobOutputVertex ssFakeTail = createFakeTail(jobGraph, numSubTasks);
        JobOutputVertex wsFakeTail = createFakeTail(jobGraph, numSubTasks);
        JobOutputVertex sync = createSync(jobGraph, numSubTasks, maxIterations);

        // ------------------ the intermediate (ss join) ----------------------
        JobTaskVertex ssJoinIntermediate = JobGraphUtils.createTask(IterationIntermediatePactTask.class,
                "Solution Set Join", jobGraph, numSubTasks, numSubTasks);
        TaskConfig ssJoinIntermediateConfig = new TaskConfig(ssJoinIntermediate.getConfiguration());
        {
            ssJoinIntermediateConfig.setIterationId(ITERATION_ID);

            // inputs
            ssJoinIntermediateConfig.addInputToGroup(0);
            ssJoinIntermediateConfig.setInputSerializer(serializer, 0);

            // output
            ssJoinIntermediateConfig.addOutputShipStrategy(ShipStrategyType.FORWARD);
            ssJoinIntermediateConfig.addOutputShipStrategy(ShipStrategyType.FORWARD);
            ssJoinIntermediateConfig.setOutputComparator(comparator, 0);
            ssJoinIntermediateConfig.setOutputComparator(comparator, 1);

            ssJoinIntermediateConfig.setOutputSerializer(serializer);

            // driver
            ssJoinIntermediateConfig.setDriver(SolutionSetSecondJoinDriver.class);
            ssJoinIntermediateConfig.setDriverStrategy(DriverStrategy.HYBRIDHASH_BUILD_SECOND);
            ssJoinIntermediateConfig.setStubWrapper(
                    new UserCodeClassWrapper<UpdateComponentIdMatch>(UpdateComponentIdMatch.class));
            ssJoinIntermediateConfig.setSolutionSetSerializer(serializer);
        }


        // -------------------------- ss tail --------------------------------
        JobTaskVertex ssTail = JobGraphUtils.createTask(IterationTailPactTask.class, "IterationSolutionSetTail",
                jobGraph, numSubTasks, numSubTasks);
        TaskConfig ssTailConfig = new TaskConfig(ssTail.getConfiguration());
        {
            ssTailConfig.setIterationId(ITERATION_ID);
            ssTailConfig.setIsSolutionSetUpdate();

            // inputs and driver
            ssTailConfig.addInputToGroup(0);
            ssTailConfig.setInputSerializer(serializer, 0);
            ssTailConfig.setInputAsynchronouslyMaterialized(0, true);
            ssTailConfig.setInputMaterializationMemory(0, MEM_PER_CONSUMER * JobGraphUtils.MEGABYTE);

            // output
            ssTailConfig.addOutputShipStrategy(ShipStrategyType.FORWARD);
            ssTailConfig.setOutputSerializer(serializer);

            // the driver
            ssTailConfig.setDriver(MapDriver.class);
            ssTailConfig.setDriverStrategy(DriverStrategy.MAP);
            ssTailConfig.setStubWrapper(new UserCodeClassWrapper<DummyMapper>(DummyMapper.class));
        }

        // -------------------------- ws tail --------------------------------
        JobTaskVertex wsTail = JobGraphUtils.createTask(IterationTailPactTask.class, "IterationWorksetTail",
                jobGraph, numSubTasks, numSubTasks);
        TaskConfig wsTailConfig = new TaskConfig(wsTail.getConfiguration());
        {
            wsTailConfig.setIterationId(ITERATION_ID);
            wsTailConfig.setIsWorksetIteration();
            wsTailConfig.setIsWorksetUpdate();

            // inputs and driver
            wsTailConfig.addInputToGroup(0);
            wsTailConfig.setInputSerializer(serializer, 0);

            // output
            wsTailConfig.addOutputShipStrategy(ShipStrategyType.FORWARD);
            wsTailConfig.setOutputSerializer(serializer);

            // the driver
            wsTailConfig.setDriver(MapDriver.class);
            wsTailConfig.setDriverStrategy(DriverStrategy.MAP);
            wsTailConfig.setStubWrapper(new UserCodeClassWrapper<DummyMapper>(DummyMapper.class));
        }

        // --------------- the wiring ---------------------

        JobGraphUtils.connect(vertices, head, ChannelType.NETWORK, DistributionPattern.BIPARTITE);
        JobGraphUtils.connect(edges, head, ChannelType.NETWORK, DistributionPattern.BIPARTITE);
        JobGraphUtils.connect(vertices, head, ChannelType.NETWORK, DistributionPattern.BIPARTITE);

        JobGraphUtils.connect(head, intermediate, ChannelType.NETWORK, DistributionPattern.BIPARTITE);
        intermediateConfig.setGateIterativeWithNumberOfEventsUntilInterrupt(0, numSubTasks);

        JobGraphUtils.connect(intermediate, ssJoinIntermediate, ChannelType.NETWORK, DistributionPattern.POINTWISE);
        ssJoinIntermediateConfig.setGateIterativeWithNumberOfEventsUntilInterrupt(0, 1);

        JobGraphUtils.connect(ssJoinIntermediate, ssTail, ChannelType.INMEMORY, DistributionPattern.POINTWISE);
        ssTailConfig.setGateIterativeWithNumberOfEventsUntilInterrupt(0, 1);

        JobGraphUtils.connect(ssJoinIntermediate, wsTail, ChannelType.INMEMORY, DistributionPattern.POINTWISE);
        wsTailConfig.setGateIterativeWithNumberOfEventsUntilInterrupt(0, 1);

        JobGraphUtils.connect(head, output, ChannelType.INMEMORY, DistributionPattern.POINTWISE);

        JobGraphUtils.connect(ssTail, ssFakeTail, ChannelType.INMEMORY, DistributionPattern.POINTWISE);
        JobGraphUtils.connect(wsTail, wsFakeTail, ChannelType.INMEMORY, DistributionPattern.POINTWISE);

        JobGraphUtils.connect(head, sync, ChannelType.NETWORK, DistributionPattern.POINTWISE);

        vertices.setVertexToShareInstancesWith(head);
        edges.setVertexToShareInstancesWith(head);

        intermediate.setVertexToShareInstancesWith(head);

        ssJoinIntermediate.setVertexToShareInstancesWith(head);
        wsTail.setVertexToShareInstancesWith(head);

        output.setVertexToShareInstancesWith(head);
        sync.setVertexToShareInstancesWith(head);

        ssTail.setVertexToShareInstancesWith(wsTail);
        ssFakeTail.setVertexToShareInstancesWith(ssTail);
        wsFakeTail.setVertexToShareInstancesWith(wsTail);

        return jobGraph;
    }

    public JobGraph createJobGraphIntermediateWorksetUpdateAndSolutionSetTail(
            String verticesPath, String edgesPath, String resultPath, int numSubTasks, int maxIterations)
            throws JobGraphDefinitionException {
        // -- init -------------------------------------------------------------------------------------------------
        final TypeSerializerFactory<?> serializer = PactRecordSerializerFactory.get();
        @SuppressWarnings("unchecked")
        final TypeComparatorFactory<?> comparator =
                new PactRecordComparatorFactory(new int[]{0}, new Class[]{PactLong.class}, new boolean[]{true});
        final TypePairComparatorFactory<?, ?> pairComparator = PactRecordPairComparatorFactory.get();

        JobGraph jobGraph = new JobGraph("Connected Components (Intermediate Workset Update, Solution Set Tail)");

        // input
        JobInputVertex vertices = createVerticesInput(jobGraph, verticesPath, numSubTasks, serializer, comparator);
        JobInputVertex edges = createEdgesInput(jobGraph, edgesPath, numSubTasks, serializer, comparator);

        // head
        JobTaskVertex head = createIterationHead(jobGraph, numSubTasks, serializer, comparator, pairComparator);
        TaskConfig headConfig = new TaskConfig(head.getConfiguration());
        headConfig.setWaitForSolutionSetUpdate();

        // intermediate
        JobTaskVertex intermediate = createIterationIntermediate(jobGraph, numSubTasks, serializer, comparator);
        TaskConfig intermediateConfig = new TaskConfig(intermediate.getConfiguration());

        // output and auxiliaries
        JobOutputVertex output = createOutput(jobGraph, resultPath, numSubTasks, serializer);
        JobOutputVertex fakeTail = createFakeTail(jobGraph, numSubTasks);
        JobOutputVertex sync = createSync(jobGraph, numSubTasks, maxIterations);

        // ------------------ the intermediate (ws update) ----------------------
        JobTaskVertex wsUpdateIntermediate =
                JobGraphUtils.createTask(IterationIntermediatePactTask.class, "WorksetUpdate", jobGraph,
                        numSubTasks, numSubTasks);
        TaskConfig wsUpdateConfig = new TaskConfig(wsUpdateIntermediate.getConfiguration());
        {
            wsUpdateConfig.setIterationId(ITERATION_ID);
            wsUpdateConfig.setIsWorksetIteration();
            wsUpdateConfig.setIsWorksetUpdate();

            // inputs
            wsUpdateConfig.addInputToGroup(0);
            wsUpdateConfig.setInputSerializer(serializer, 0);

            // output
            wsUpdateConfig.addOutputShipStrategy(ShipStrategyType.FORWARD);
            wsUpdateConfig.setOutputComparator(comparator, 0);

            wsUpdateConfig.setOutputSerializer(serializer);

            // driver
            wsUpdateConfig.setDriver(SolutionSetSecondJoinDriver.class);
            wsUpdateConfig.setDriverStrategy(DriverStrategy.HYBRIDHASH_BUILD_SECOND);
            wsUpdateConfig.setStubWrapper(new UserCodeClassWrapper<UpdateComponentIdMatch>(
                    UpdateComponentIdMatch.class));
            wsUpdateConfig.setSolutionSetSerializer(serializer);
        }

        // -------------------------- ss tail --------------------------------
        JobTaskVertex ssTail =
                JobGraphUtils.createTask(IterationTailPactTask.class, "IterationSolutionSetTail", jobGraph,
                        numSubTasks, numSubTasks);
        TaskConfig ssTailConfig = new TaskConfig(ssTail.getConfiguration());
        {
            ssTailConfig.setIterationId(ITERATION_ID);
            ssTailConfig.setIsSolutionSetUpdate();

            // inputs and driver
            ssTailConfig.addInputToGroup(0);
            ssTailConfig.setInputSerializer(serializer, 0);

            // output
            ssTailConfig.addOutputShipStrategy(ShipStrategyType.FORWARD);
            ssTailConfig.setOutputSerializer(serializer);

            // the driver
            ssTailConfig.setDriver(MapDriver.class);
            ssTailConfig.setDriverStrategy(DriverStrategy.MAP);
            ssTailConfig.setStubWrapper(new UserCodeClassWrapper<DummyMapper>(DummyMapper.class));
        }

        // edges

        JobGraphUtils.connect(vertices, head, ChannelType.NETWORK, DistributionPattern.BIPARTITE);
        JobGraphUtils.connect(edges, head, ChannelType.NETWORK, DistributionPattern.BIPARTITE);
        JobGraphUtils.connect(vertices, head, ChannelType.NETWORK, DistributionPattern.BIPARTITE);

        JobGraphUtils.connect(head, intermediate, ChannelType.NETWORK, DistributionPattern.BIPARTITE);
        intermediateConfig.setGateIterativeWithNumberOfEventsUntilInterrupt(0, numSubTasks);

        JobGraphUtils.connect(intermediate, wsUpdateIntermediate, ChannelType.NETWORK,
                DistributionPattern.POINTWISE);
        wsUpdateConfig.setGateIterativeWithNumberOfEventsUntilInterrupt(0, 1);

        JobGraphUtils.connect(wsUpdateIntermediate, ssTail, ChannelType.INMEMORY, DistributionPattern.POINTWISE);
        ssTailConfig.setGateIterativeWithNumberOfEventsUntilInterrupt(0, 1);

        JobGraphUtils.connect(head, output, ChannelType.INMEMORY, DistributionPattern.POINTWISE);

        JobGraphUtils.connect(ssTail, fakeTail, ChannelType.INMEMORY, DistributionPattern.POINTWISE);

        JobGraphUtils.connect(head, sync, ChannelType.NETWORK, DistributionPattern.POINTWISE);

        vertices.setVertexToShareInstancesWith(head);
        edges.setVertexToShareInstancesWith(head);

        intermediate.setVertexToShareInstancesWith(head);

        wsUpdateIntermediate.setVertexToShareInstancesWith(head);
        ssTail.setVertexToShareInstancesWith(head);

        output.setVertexToShareInstancesWith(head);
        sync.setVertexToShareInstancesWith(head);

        fakeTail.setVertexToShareInstancesWith(ssTail);

        return jobGraph;
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Intermediate solution set update and workset tail
    // -----------------------------------------------------------------------------------------------------------------


    public JobGraph createJobGraphSolutionSetUpdateAndWorksetTail(
            String verticesPath, String edgesPath, String resultPath, int numSubTasks, int maxIterations)
            throws JobGraphDefinitionException {
        // -- init -------------------------------------------------------------------------------------------------
        final TypeSerializerFactory<?> serializer = PactRecordSerializerFactory.get();
        @SuppressWarnings("unchecked")
        final TypeComparatorFactory<?> comparator =
                new PactRecordComparatorFactory(new int[]{0}, new Class[]{PactLong.class}, new boolean[]{true});
        final TypePairComparatorFactory<?, ?> pairComparator = PactRecordPairComparatorFactory.get();

        JobGraph jobGraph = new JobGraph("Connected Components (Intermediate Solution Set Update, Workset Tail)");

        // input
        JobInputVertex vertices = createVerticesInput(jobGraph, verticesPath, numSubTasks, serializer, comparator);
        JobInputVertex edges = createEdgesInput(jobGraph, edgesPath, numSubTasks, serializer, comparator);

        // head
        JobTaskVertex head = createIterationHead(jobGraph, numSubTasks, serializer, comparator, pairComparator);

        // intermediate
        JobTaskVertex intermediate = createIterationIntermediate(jobGraph, numSubTasks, serializer, comparator);
        TaskConfig intermediateConfig = new TaskConfig(intermediate.getConfiguration());

        // output and auxiliaries
        JobOutputVertex output = createOutput(jobGraph, resultPath, numSubTasks, serializer);
        JobOutputVertex fakeTail = createFakeTail(jobGraph, numSubTasks);
        JobOutputVertex sync = createSync(jobGraph, numSubTasks, maxIterations);

        // ------------------ the intermediate (ss update) ----------------------
        JobTaskVertex ssJoinIntermediate = JobGraphUtils.createTask(IterationIntermediatePactTask.class,
                "Solution Set Update", jobGraph, numSubTasks, numSubTasks);
        TaskConfig ssJoinIntermediateConfig = new TaskConfig(ssJoinIntermediate.getConfiguration());
        {
            ssJoinIntermediateConfig.setIterationId(ITERATION_ID);
            ssJoinIntermediateConfig.setIsSolutionSetUpdate();
            ssJoinIntermediateConfig.setIsSolutionSetUpdateWithoutReprobe();

            // inputs
            ssJoinIntermediateConfig.addInputToGroup(0);
            ssJoinIntermediateConfig.setInputSerializer(serializer, 0);

            // output
            ssJoinIntermediateConfig.addOutputShipStrategy(ShipStrategyType.FORWARD);
            ssJoinIntermediateConfig.setOutputComparator(comparator, 0);

            ssJoinIntermediateConfig.setOutputSerializer(serializer);

            // driver
            ssJoinIntermediateConfig.setDriver(SolutionSetSecondJoinDriver.class);
            ssJoinIntermediateConfig.setDriverStrategy(DriverStrategy.HYBRIDHASH_BUILD_SECOND);
            ssJoinIntermediateConfig.setStubWrapper(
                    new UserCodeClassWrapper<UpdateComponentIdMatch>(UpdateComponentIdMatch.class));
            ssJoinIntermediateConfig.setSolutionSetSerializer(serializer);
        }

        // -------------------------- ws tail --------------------------------
        JobTaskVertex wsTail = JobGraphUtils.createTask(IterationTailPactTask.class, "IterationWorksetTail",
                jobGraph, numSubTasks, numSubTasks);
        TaskConfig wsTailConfig = new TaskConfig(wsTail.getConfiguration());
        {
            wsTailConfig.setIterationId(ITERATION_ID);
            wsTailConfig.setIsWorksetIteration();
            wsTailConfig.setIsWorksetUpdate();

            // inputs and driver
            wsTailConfig.addInputToGroup(0);
            wsTailConfig.setInputSerializer(serializer, 0);

            // output
            wsTailConfig.addOutputShipStrategy(ShipStrategyType.FORWARD);
            wsTailConfig.setOutputSerializer(serializer);

            // the driver
            wsTailConfig.setDriver(MapDriver.class);
            wsTailConfig.setDriverStrategy(DriverStrategy.MAP);
            wsTailConfig.setStubWrapper(new UserCodeClassWrapper<DummyMapper>(DummyMapper.class));
        }

        // --------------- the wiring ---------------------

        JobGraphUtils.connect(vertices, head, ChannelType.NETWORK, DistributionPattern.BIPARTITE);
        JobGraphUtils.connect(edges, head, ChannelType.NETWORK, DistributionPattern.BIPARTITE);
        JobGraphUtils.connect(vertices, head, ChannelType.NETWORK, DistributionPattern.BIPARTITE);

        JobGraphUtils.connect(head, intermediate, ChannelType.NETWORK, DistributionPattern.BIPARTITE);
        intermediateConfig.setGateIterativeWithNumberOfEventsUntilInterrupt(0, numSubTasks);

        JobGraphUtils.connect(intermediate, ssJoinIntermediate, ChannelType.NETWORK, DistributionPattern.POINTWISE);
        ssJoinIntermediateConfig.setGateIterativeWithNumberOfEventsUntilInterrupt(0, 1);

        JobGraphUtils.connect(ssJoinIntermediate, wsTail, ChannelType.INMEMORY, DistributionPattern.POINTWISE);
        wsTailConfig.setGateIterativeWithNumberOfEventsUntilInterrupt(0, 1);

        JobGraphUtils.connect(head, output, ChannelType.INMEMORY, DistributionPattern.POINTWISE);

        JobGraphUtils.connect(wsTail, fakeTail, ChannelType.INMEMORY, DistributionPattern.POINTWISE);

        JobGraphUtils.connect(head, sync, ChannelType.NETWORK, DistributionPattern.POINTWISE);

        vertices.setVertexToShareInstancesWith(head);
        edges.setVertexToShareInstancesWith(head);

        intermediate.setVertexToShareInstancesWith(head);

        ssJoinIntermediate.setVertexToShareInstancesWith(head);
        wsTail.setVertexToShareInstancesWith(head);

        output.setVertexToShareInstancesWith(head);
        sync.setVertexToShareInstancesWith(head);

        fakeTail.setVertexToShareInstancesWith(wsTail);

        return jobGraph;
    }

    public static final class DummyMapper extends MapStub {
        @Override
        public void map(PactRecord rec, Collector<PactRecord> out) {
            out.collect(rec);
        }
    }
}
