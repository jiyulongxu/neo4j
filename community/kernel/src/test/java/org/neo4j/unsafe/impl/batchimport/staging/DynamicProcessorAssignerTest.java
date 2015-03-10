/**
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.unsafe.impl.batchimport.staging;

import org.junit.Test;

import java.util.Arrays;

import org.neo4j.unsafe.impl.batchimport.Configuration;
import org.neo4j.unsafe.impl.batchimport.stats.Keys;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import static org.neo4j.unsafe.impl.batchimport.staging.ControlledStep.stepWithStats;
import static org.neo4j.unsafe.impl.batchimport.stats.Keys.avg_processing_time;
import static org.neo4j.unsafe.impl.batchimport.stats.Keys.done_batches;

public class DynamicProcessorAssignerTest
{
    @Test
    public void shouldAssignAdditionalCPUToBottleNeckStep() throws Exception
    {
        // GIVEN
        Configuration config = movingAverageConfig( 10 );
        DynamicProcessorAssigner assigner = new DynamicProcessorAssigner( config, 5 );

        ControlledStep<?> slowStep = new ControlledStep<>( "slow", true );
        slowStep.setStat( Keys.avg_processing_time, 10 );
        slowStep.setStat( Keys.done_batches, 10 );

        ControlledStep<?> fastStep = new ControlledStep<>( "fast", true );
        fastStep.setStat( Keys.avg_processing_time, 2 );
        fastStep.setStat( Keys.done_batches, 10 );

        StageExecution[] execution = executionOf( config, slowStep, fastStep );
        assigner.start( execution );

        // WHEN
        assigner.check( execution );

        // THEN
        assertEquals( 5, slowStep.numberOfProcessors() );
        assertEquals( 1, fastStep.numberOfProcessors() );
    }

    @Test
    public void shouldRemoveCPUsFromWayTooFastStep() throws Exception
    {
        // GIVEN
        Configuration config = movingAverageConfig( 10 );
        // available processors = 2 is enough because it will see the fast step as only using 20% of a processor
        // and it rounds down. So there's room for assigning one more.
        DynamicProcessorAssigner assigner = new DynamicProcessorAssigner( config, 3 );

        ControlledStep<?> slowStep = spy( new ControlledStep<>( "slow", true ) );
        slowStep.setStat( Keys.avg_processing_time, 10 );
        slowStep.setStat( Keys.done_batches, 10 );

        ControlledStep<?> fastStep = spy( new ControlledStep<>( "fast", true, 2 ) );
        fastStep.setStat( Keys.avg_processing_time, 2 );
        fastStep.setStat( Keys.done_batches, 10 );

        StageExecution[] execution = executionOf( config, slowStep, fastStep );
        assigner.start( execution );

        // WHEN first checking
        assigner.check( execution );
        // THEN one additional processor will be added to the slow step
        verify( fastStep, times( 0 ) ).incrementNumberOfProcessors();
        verify( fastStep, times( 1 ) ).decrementNumberOfProcessors();
    }

    @Test
    public void shouldHandleZeroAverage() throws Exception
    {
        // GIVEN
        Configuration config = movingAverageConfig( 10 );
        DynamicProcessorAssigner assigner = new DynamicProcessorAssigner( config, 5 );

        ControlledStep<?> aStep = new ControlledStep<>( "slow", true );
        aStep.setStat( Keys.avg_processing_time, 0 );
        aStep.setStat( Keys.done_batches, 0 );

        ControlledStep<?> anotherStep = new ControlledStep<>( "fast", true );
        anotherStep.setStat( Keys.avg_processing_time, 0 );
        anotherStep.setStat( Keys.done_batches, 0 );

        StageExecution[] execution = executionOf( config, aStep, anotherStep );
        assigner.start( execution );

        // WHEN
        assigner.check( execution );

        // THEN
        assertEquals( 1, aStep.numberOfProcessors() );
        assertEquals( 1, anotherStep.numberOfProcessors() );
    }

    @Test
    public void shouldRemoveCPUsFromTooFastStepEvenIfThereIsAWayFaster() throws Exception
    {
        // The point is that not only the fastest step is subject to have processors removed,
        // it's the relationship between all pairs of steps. This is important since the DPA has got
        // a max permit count of processors to assign, so reclaiming unnecessary assignments can
        // have those be assigned to a more appropriate step instead, where it will benefit the Stage more.

        // GIVEN
        Configuration config = movingAverageConfig( 10 );
        DynamicProcessorAssigner assigner = new DynamicProcessorAssigner( config, 5 );
        Step<?> wayFastest = stepWithStats( avg_processing_time, 0L, done_batches, 20L );
        Step<?> fast =  spy( stepWithStats( avg_processing_time, 100L, done_batches, 20L ).setNumberOfProcessors( 3 ) );
        Step<?> slow = stepWithStats( avg_processing_time, 200L, done_batches, 20L );
        StageExecution[] execution = executionOf( config, slow, wayFastest, fast );
        assigner.start( execution );

        // WHEN
        assigner.check( execution );

        // THEN
        verify( fast ).decrementNumberOfProcessors();
    }

    private Configuration movingAverageConfig( final int movingAverage )
    {
        return new Configuration.Default()
        {
            @Override
            public int movingAverageSize()
            {
                return movingAverage;
            }
        };
    }

    private StageExecution[] executionOf( Configuration config, Step<?>... steps )
    {
        StageExecution execution = new StageExecution( "Test", config, Arrays.asList( steps ), true );
        return new StageExecution[] {execution};
    }
}
