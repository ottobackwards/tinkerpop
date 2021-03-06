/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.traversal;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.MapConfiguration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.tinkerpop.gremlin.process.computer.Computer;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.computer.traversal.strategy.decoration.VertexProgramStrategy;
import org.apache.tinkerpop.gremlin.process.computer.util.GraphComputerHelper;
import org.apache.tinkerpop.gremlin.process.remote.RemoteConnection;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SackStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SideEffectStrategy;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.util.function.ConstantSupplier;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A {@code TraversalSource} is used to create {@link Traversal} instances.
 * A traversal source can generate any number of {@link Traversal} instances.
 * A traversal source is primarily composed of a {@link Graph} and a {@link TraversalStrategies}.
 * Various {@code withXXX}-based methods are used to configure the traversal strategies (called "configurations").
 * Various other methods (dependent on the traversal source type) will then generate a traversal given the graph and configured strategies (called "spawns").
 * A traversal source is immutable in that fluent chaining of configurations create new traversal sources.
 * This is unlike {@link Traversal} and {@link GraphComputer}, where chained methods configure the same instance.
 * Every traversal source implementation must maintain two constructors to enable proper reflection-based construction.
 * <p/>
 * {@code TraversalSource(Graph)} and {@code TraversalSource(Graph,TraversalStrategies)}
 *
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public interface TraversalSource extends Cloneable, AutoCloseable {

    public static final String GREMLIN_REMOTE = "gremlin.remote.";
    public static final String GREMLIN_REMOTE_CONNECTION_CLASS = GREMLIN_REMOTE + "remoteConnectionClass";

    /**
     * Get the {@link TraversalStrategies} associated with this traversal source.
     *
     * @return the traversal strategies of the traversal source
     */
    public TraversalStrategies getStrategies();

    /**
     * Get the {@link Graph) associated with this traversal source.
     *
     * @return the graph of the traversal source
     */
    public Graph getGraph();

    /**
     * Get the {@link Bytecode} associated with the current state of this traversal source.
     *
     * @return the traversal source byte code
     */
    public Bytecode getBytecode();

    /////////////////////////////

    public static class Symbols {

        private Symbols() {
            // static fields only
        }

        public static final String withBindings = "withBindings";
        public static final String withSack = "withSack";
        public static final String withStrategies = "withStrategies";
        public static final String withoutStrategies = "withoutStrategies";
        public static final String withComputer = "withComputer";
        public static final String withSideEffect = "withSideEffect";
        public static final String withRemote = "withRemote";
    }

    /////////////////////////////

    /**
     * Add an arbitrary collection of {@link TraversalStrategy} instances to the traversal source.
     * The map configurations must have a <code>strategy</code> key that is the name of the strategy class.
     *
     * @param traversalStrategyConfigurations a collection of traversal strategies to add by configuration
     * @return a new traversal source with updated strategies
     */
    @SuppressWarnings({"unchecked"})
    public default TraversalSource withStrategies(final Map<String, Object>... traversalStrategyConfigurations) {
        final TraversalSource clone = this.clone();
        final List<TraversalStrategy<?>> strategies = new ArrayList<>();
        for (final Map<String, Object> map : traversalStrategyConfigurations) {
            try {
                final TraversalStrategy<?> traversalStrategy = (TraversalStrategy) ((1 == map.size()) ?
                        Class.forName((String) map.get("strategy")).getMethod("instance").invoke(null) :
                        Class.forName((String) map.get("strategy")).getMethod("create", Configuration.class).invoke(null, new MapConfiguration(map)));
                strategies.add(traversalStrategy);
            } catch (final ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }
        clone.getStrategies().addStrategies(strategies.toArray(new TraversalStrategy[strategies.size()]));
        clone.getBytecode().addSource(TraversalSource.Symbols.withStrategies, traversalStrategyConfigurations);
        return clone;
    }

    /**
     * Remove an arbitrary collection of {@link TraversalStrategy} classes from the traversal source.
     *
     * @param traversalStrategyNames a collection of traversal strategy class names to remove
     * @return a new traversal source with updated strategies
     */
    @SuppressWarnings({"unchecked"})
    public default TraversalSource withoutStrategies(final String... traversalStrategyNames) {
        final TraversalSource clone = this.clone();
        final List<Class<TraversalStrategy>> strategies = new ArrayList<>();
        for (final String name : traversalStrategyNames) {
            try {
                strategies.add((Class) Class.forName(name));
            } catch (final ClassNotFoundException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }
        clone.getStrategies().removeStrategies(strategies.toArray(new Class[strategies.size()]));
        clone.getBytecode().addSource(Symbols.withoutStrategies, traversalStrategyNames);
        return clone;
    }

    /**
     * Add an arbitrary collection of {@link TraversalStrategy} instances to the traversal source.
     *
     * @param traversalStrategies a colleciton of traversal strategies to add
     * @return a new traversal source with updated strategies
     */
    public default TraversalSource withStrategies(final TraversalStrategy... traversalStrategies) {
        final TraversalSource clone = this.clone();
        clone.getStrategies().addStrategies(traversalStrategies);
        clone.getBytecode().addSource(TraversalSource.Symbols.withStrategies, traversalStrategies);
        return clone;
    }

    /**
     * Remove an arbitrary collection of {@link TraversalStrategy} classes from the traversal source.
     *
     * @param traversalStrategyClasses a collection of traversal strategy classes to remove
     * @return a new traversal source with updated strategies
     */
    @SuppressWarnings({"unchecked", "varargs"})
    public default TraversalSource withoutStrategies(final Class<? extends TraversalStrategy>... traversalStrategyClasses) {
        final TraversalSource clone = this.clone();
        clone.getStrategies().removeStrategies(traversalStrategyClasses);
        clone.getBytecode().addSource(TraversalSource.Symbols.withoutStrategies, traversalStrategyClasses);
        return clone;
    }

    /**
     * Using the provided {@link Bindings} to create {@link org.apache.tinkerpop.gremlin.process.traversal.Bytecode.Binding}.
     * The bindings serve as a relay for ensure bound arguments are encoded as {@link org.apache.tinkerpop.gremlin.process.traversal.Bytecode.Binding} in {@link Bytecode}.
     *
     * @param bindings the bindings instance to use
     * @return a new traversal source with set bindings
     */
    public default TraversalSource withBindings(final Bindings bindings) {
        final TraversalSource clone = this.clone();
        clone.getBytecode().addSource(Symbols.withBindings, bindings);
        return clone;
    }

    /**
     * Configure a {@link GraphComputer} to be used for the execution of subsequently spawned traversal.
     * This adds a {@link VertexProgramStrategy} to the strategies.
     *
     * @param computerConfiguration key/value pair map for configuring a {@link Computer}
     * @return a new traversal source with updated strategies
     */
    public default TraversalSource withComputer(final Map<String, Object> computerConfiguration) {
        final TraversalSource clone = this.clone();
        clone.getStrategies().addStrategies(GraphComputerHelper.getTraversalStrategies(this, Computer.compute(new MapConfiguration(computerConfiguration))));
        clone.getBytecode().addSource(TraversalSource.Symbols.withComputer, computerConfiguration);
        return clone;
    }

    /**
     * Add a {@link Computer} that will generate a {@link GraphComputer} from the {@link Graph} that will be used to execute the traversal.
     * This adds a {@link VertexProgramStrategy} to the strategies.
     *
     * @param computer a builder to generate a graph computer from the graph
     * @return a new traversal source with updated strategies
     */
    public default TraversalSource withComputer(final Computer computer) {
        final TraversalSource clone = this.clone();
        clone.getStrategies().addStrategies(GraphComputerHelper.getTraversalStrategies(this, computer));
        clone.getBytecode().addSource(TraversalSource.Symbols.withComputer, computer);
        return clone;
    }

    /**
     * Add a {@link GraphComputer} class used to execute the traversal.
     * This adds a {@link VertexProgramStrategy} to the strategies.
     *
     * @param graphComputerClass the graph computer class
     * @return a new traversal source with updated strategies
     */
    public default TraversalSource withComputer(final Class<? extends GraphComputer> graphComputerClass) {
        final TraversalSource clone = this.clone();
        clone.getStrategies().addStrategies(GraphComputerHelper.getTraversalStrategies(this, Computer.compute(graphComputerClass)));
        clone.getBytecode().addSource(TraversalSource.Symbols.withComputer, graphComputerClass);
        return clone;
    }

    /**
     * Add the standard {@link GraphComputer} of the graph that will be used to execute the traversal.
     * This adds a {@link VertexProgramStrategy} to the strategies.
     *
     * @return a new traversal source with updated strategies
     */
    public default TraversalSource withComputer() {
        final TraversalSource clone = this.clone();
        clone.getStrategies().addStrategies(GraphComputerHelper.getTraversalStrategies(this, Computer.compute()));
        clone.getBytecode().addSource(TraversalSource.Symbols.withComputer);
        return clone;
    }

    /**
     * Add a sideEffect to be used throughout the life of a spawned {@link Traversal}.
     * This adds a {@link org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SideEffectStrategy} to the strategies.
     *
     * @param key          the key of the sideEffect
     * @param initialValue a supplier that produces the initial value of the sideEffect
     * @param reducer      a reducer to merge sideEffect mutations into a single result
     * @return a new traversal source with updated strategies
     */
    public default <A> TraversalSource withSideEffect(final String key, final Supplier<A> initialValue, final BinaryOperator<A> reducer) {
        final TraversalSource clone = this.clone();
        SideEffectStrategy.addSideEffect(clone.getStrategies(), key, (A) initialValue, reducer);
        clone.getBytecode().addSource(TraversalSource.Symbols.withSideEffect, key, initialValue, reducer);
        return clone;
    }

    /**
     * Add a sideEffect to be used throughout the life of a spawned {@link Traversal}.
     * This adds a {@link org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SideEffectStrategy} to the strategies.
     *
     * @param key          the key of the sideEffect
     * @param initialValue the initial value of the sideEffect
     * @param reducer      a reducer to merge sideEffect mutations into a single result
     * @return a new traversal source with updated strategies
     */
    public default <A> TraversalSource withSideEffect(final String key, final A initialValue, final BinaryOperator<A> reducer) {
        final TraversalSource clone = this.clone();
        SideEffectStrategy.addSideEffect(clone.getStrategies(), key, initialValue, reducer);
        clone.getBytecode().addSource(TraversalSource.Symbols.withSideEffect, key, initialValue, reducer);
        return clone;
    }

    /**
     * Add a sideEffect to be used throughout the life of a spawned {@link Traversal}.
     * This adds a {@link org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SideEffectStrategy} to the strategies.
     *
     * @param key          the key of the sideEffect
     * @param initialValue a supplier that produces the initial value of the sideEffect
     * @return a new traversal source with updated strategies
     */
    public default <A> TraversalSource withSideEffect(final String key, final Supplier<A> initialValue) {
        final TraversalSource clone = this.clone();
        SideEffectStrategy.addSideEffect(clone.getStrategies(), key, (A) initialValue, null);
        clone.getBytecode().addSource(TraversalSource.Symbols.withSideEffect, key, initialValue);
        return clone;
    }

    /**
     * Add a sideEffect to be used throughout the life of a spawned {@link Traversal}.
     * This adds a {@link org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SideEffectStrategy} to the strategies.
     *
     * @param key          the key of the sideEffect
     * @param initialValue the initial value of the sideEffect
     * @return a new traversal source with updated strategies
     */
    public default <A> TraversalSource withSideEffect(final String key, final A initialValue) {
        final TraversalSource clone = this.clone();
        SideEffectStrategy.addSideEffect(clone.getStrategies(), key, initialValue, null);
        clone.getBytecode().addSource(TraversalSource.Symbols.withSideEffect, key, initialValue);
        return clone;
    }

    /**
     * Add a sack to be used throughout the life of a spawned {@link Traversal}.
     * This adds a {@link org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SackStrategy} to the strategies.
     *
     * @param initialValue  a supplier that produces the initial value of the sideEffect
     * @param splitOperator the sack split operator
     * @param mergeOperator the sack merge operator
     * @return a new traversal source with updated strategies
     */
    public default <A> TraversalSource withSack(final Supplier<A> initialValue, final UnaryOperator<A> splitOperator, final BinaryOperator<A> mergeOperator) {
        final TraversalSource clone = this.clone();
        clone.getStrategies().addStrategies(SackStrategy.<A>build().initialValue(initialValue).splitOperator(splitOperator).mergeOperator(mergeOperator).create());
        clone.getBytecode().addSource(TraversalSource.Symbols.withSack, initialValue, splitOperator, mergeOperator);
        return clone;
    }

    /**
     * Add a sack to be used throughout the life of a spawned {@link Traversal}.
     * This adds a {@link org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SackStrategy} to the strategies.
     *
     * @param initialValue  the initial value of the sideEffect
     * @param splitOperator the sack split operator
     * @param mergeOperator the sack merge operator
     * @return a new traversal source with updated strategies
     */
    public default <A> TraversalSource withSack(final A initialValue, final UnaryOperator<A> splitOperator, final BinaryOperator<A> mergeOperator) {
        final TraversalSource clone = this.clone();
        clone.getStrategies().addStrategies(SackStrategy.<A>build().initialValue(new ConstantSupplier<>(initialValue)).splitOperator(splitOperator).mergeOperator(mergeOperator).create());
        clone.getBytecode().addSource(TraversalSource.Symbols.withSack, initialValue, splitOperator, mergeOperator);
        return clone;
    }

    /**
     * Add a sack to be used throughout the life of a spawned {@link Traversal}.
     * This adds a {@link org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SackStrategy} to the strategies.
     *
     * @param initialValue the initial value of the sideEffect
     * @return a new traversal source with updated strategies
     */
    public default <A> TraversalSource withSack(final A initialValue) {
        final TraversalSource clone = this.clone();
        clone.getStrategies().addStrategies(SackStrategy.<A>build().initialValue(new ConstantSupplier<>(initialValue)).create());
        clone.getBytecode().addSource(TraversalSource.Symbols.withSack, initialValue);
        return clone;
    }

    /**
     * Add a sack to be used throughout the life of a spawned {@link Traversal}.
     * This adds a {@link org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SackStrategy} to the strategies.
     *
     * @param initialValue a supplier that produces the initial value of the sideEffect
     * @return a new traversal source with updated strategies
     */
    public default <A> TraversalSource withSack(final Supplier<A> initialValue) {
        final TraversalSource clone = this.clone();
        clone.getStrategies().addStrategies(SackStrategy.<A>build().initialValue(initialValue).create());
        clone.getBytecode().addSource(TraversalSource.Symbols.withSack, initialValue);
        return clone;
    }

    /**
     * Add a sack to be used throughout the life of a spawned {@link Traversal}.
     * This adds a {@link org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SackStrategy} to the strategies.
     *
     * @param initialValue  a supplier that produces the initial value of the sideEffect
     * @param splitOperator the sack split operator
     * @return a new traversal source with updated strategies
     */
    public default <A> TraversalSource withSack(final Supplier<A> initialValue, final UnaryOperator<A> splitOperator) {
        final TraversalSource clone = this.clone();
        clone.getStrategies().addStrategies(SackStrategy.<A>build().initialValue(initialValue).splitOperator(splitOperator).create());
        clone.getBytecode().addSource(TraversalSource.Symbols.withSack, initialValue, splitOperator);
        return clone;
    }

    /**
     * Add a sack to be used throughout the life of a spawned {@link Traversal}.
     * This adds a {@link org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SackStrategy} to the strategies.
     *
     * @param initialValue  the initial value of the sideEffect
     * @param splitOperator the sack split operator
     * @return a new traversal source with updated strategies
     */
    public default <A> TraversalSource withSack(final A initialValue, final UnaryOperator<A> splitOperator) {
        final TraversalSource clone = this.clone();
        clone.getStrategies().addStrategies(SackStrategy.<A>build().initialValue(new ConstantSupplier<>(initialValue)).splitOperator(splitOperator).create());
        clone.getBytecode().addSource(TraversalSource.Symbols.withSack, initialValue, splitOperator);
        return clone;
    }

    /**
     * Add a sack to be used throughout the life of a spawned {@link Traversal}.
     * This adds a {@link org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SackStrategy} to the strategies.
     *
     * @param initialValue  a supplier that produces the initial value of the sideEffect
     * @param mergeOperator the sack merge operator
     * @return a new traversal source with updated strategies
     */
    public default <A> TraversalSource withSack(final Supplier<A> initialValue, final BinaryOperator<A> mergeOperator) {
        final TraversalSource clone = this.clone();
        clone.getStrategies().addStrategies(SackStrategy.<A>build().initialValue(initialValue).mergeOperator(mergeOperator).create());
        clone.getBytecode().addSource(TraversalSource.Symbols.withSack, initialValue, mergeOperator);
        return clone;
    }

    /**
     * Add a sack to be used throughout the life of a spawned {@link Traversal}.
     * This adds a {@link org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SackStrategy} to the strategies.
     *
     * @param initialValue  the initial value of the sideEffect
     * @param mergeOperator the sack merge operator
     * @return a new traversal source with updated strategies
     */
    public default <A> TraversalSource withSack(final A initialValue, final BinaryOperator<A> mergeOperator) {
        final TraversalSource clone = this.clone();
        clone.getStrategies().addStrategies(SackStrategy.<A>build().initialValue(new ConstantSupplier<>(initialValue)).mergeOperator(mergeOperator).create());
        clone.getBytecode().addSource(TraversalSource.Symbols.withSack, initialValue, mergeOperator);
        return clone;
    }

    /**
     * Configures the {@code TraversalSource} as a "remote" to issue the {@link Traversal} for execution elsewhere.
     * Expects key for {@link #GREMLIN_REMOTE_CONNECTION_CLASS} as well as any configuration required by
     * the underlying {@link RemoteConnection} which will be instantiated. Note that the {@code Configuration} object
     * is passed down without change to the creation of the {@link RemoteConnection} instance.
     */
    public default TraversalSource withRemote(final Configuration conf) {
        if (!conf.containsKey(GREMLIN_REMOTE_CONNECTION_CLASS))
            throw new IllegalArgumentException("Configuration must contain the '" + GREMLIN_REMOTE_CONNECTION_CLASS + "' key");

        final RemoteConnection remoteConnection;
        try {
            final Class<? extends RemoteConnection> clazz = Class.forName(conf.getString(GREMLIN_REMOTE_CONNECTION_CLASS)).asSubclass(RemoteConnection.class);
            final Constructor<? extends RemoteConnection> ctor = clazz.getConstructor(Configuration.class);
            remoteConnection = ctor.newInstance(conf);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }

        return withRemote(remoteConnection);
    }

    /**
     * Configures the {@code TraversalSource} as a "remote" to issue the {@link Traversal} for execution elsewhere.
     * Calls {@link #withRemote(Configuration)} after reading the properties file specified.
     */
    public default TraversalSource withRemote(final String configFile) throws Exception {
        return withRemote(new PropertiesConfiguration(configFile));
    }

    /**
     * Configures the {@code TraversalSource} as a "remote" to issue the {@link Traversal} for execution elsewhere.
     * Implementations should track {@link RemoteConnection} instances that are created and call
     * {@link RemoteConnection#close()} on them when the {@code TraversalSource} itself is closed.
     *
     * @param connection the {@link RemoteConnection} instance to use to submit the {@link Traversal}.
     */
    public TraversalSource withRemote(final RemoteConnection connection);

    public default Optional<Class> getAnonymousTraversalClass() {
        return Optional.empty();
    }

    /**
     * The clone-method should be used to create immutable traversal sources with each call to a configuration "withXXX"-method.
     * The clone-method should clone the {@link Bytecode}, {@link TraversalStrategies}, mutate the cloned strategies accordingly,
     * and then return the cloned traversal source leaving the original unaltered.
     *
     * @return the cloned traversal source
     */
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public TraversalSource clone();

    @Override
    public default void close() throws Exception {
        // do nothing
    }

    /**
     * @deprecated As of release 3.2.0. Please use {@link Graph#traversal(Class)}.
     */
    @Deprecated
    public interface Builder<C extends TraversalSource> extends Serializable {

        /**
         * @deprecated As of release 3.2.0. Please use {@link Graph#traversal(Class)}.
         */
        @Deprecated
        public Builder engine(final TraversalEngine.Builder engine);

        /**
         * @deprecated As of release 3.2.0. Please use {@link Graph#traversal(Class)}.
         */
        @Deprecated
        public Builder with(final TraversalStrategy strategy);

        /**
         * @deprecated As of release 3.2.0. Please use {@link Graph#traversal(Class)}.
         */
        @Deprecated
        public Builder without(final Class<? extends TraversalStrategy> strategyClass);

        /**
         * @deprecated As of release 3.2.0. Please use {@link Graph#traversal(Class)}.
         */
        @Deprecated
        public C create(final Graph graph);
    }

}
