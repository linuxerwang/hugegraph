package com.baidu.hugegraph;

import java.util.Iterator;
import java.util.function.Function;

import org.apache.commons.configuration.Configuration;
import org.apache.tinkerpop.gremlin.process.computer.GraphComputer;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategies;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Transaction;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.AbstractThreadedTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baidu.hugegraph.backend.BackendException;
import com.baidu.hugegraph.backend.query.Query;
import com.baidu.hugegraph.backend.serializer.AbstractSerializer;
import com.baidu.hugegraph.backend.serializer.SerializerFactory;
import com.baidu.hugegraph.backend.store.BackendProviderFactory;
import com.baidu.hugegraph.backend.store.BackendStore;
import com.baidu.hugegraph.backend.store.BackendStoreProvider;
import com.baidu.hugegraph.backend.tx.GraphTransaction;
import com.baidu.hugegraph.backend.tx.SchemaTransaction;
import com.baidu.hugegraph.configuration.ConfigSpace;
import com.baidu.hugegraph.configuration.HugeConfiguration;
import com.baidu.hugegraph.schema.HugeSchemaManager;
import com.baidu.hugegraph.schema.SchemaManager;
import com.baidu.hugegraph.structure.HugeFeatures;
import com.baidu.hugegraph.traversal.optimize.HugeGraphStepStrategy;
import com.baidu.hugegraph.traversal.optimize.HugeVertexStepStrategy;

/**
 * Created by jishilei on 17/3/17.
 */
public class HugeGraph implements Graph {

    private static final Logger logger = LoggerFactory.getLogger(HugeGraph.class);

    static {
        TraversalStrategies strategies = null;
        strategies = TraversalStrategies.GlobalCache.getStrategies(
                Graph.class).clone();
        strategies.addStrategies(
                HugeVertexStepStrategy.instance(),
                HugeGraphStepStrategy.instance());
        TraversalStrategies.GlobalCache.registerStrategies(
                HugeGraph.class, strategies);
    }

    protected HugeConfiguration configuration = null;
    protected HugeFeatures features = null;

    // store provider like Cassandra
    private BackendStoreProvider storeProvider = null;

    // default transactions
    private GraphTransaction graphTransaction = null;
    private SchemaTransaction schemaTransaction = null;

    /**
     * Construct a HugeGraph instance
     * @return
     */
    public static HugeGraph open(final Configuration configuration) {
        return new HugeGraph(configuration);
    }

    public HugeGraph(Configuration configuration) {
        this(new HugeConfiguration(configuration));
    }

    public HugeGraph(HugeConfiguration configuration) {
        this.configuration = configuration;
        // TODO : get supportsPersistence from configuration;
        this.features = new HugeFeatures(true);

        try {
            this.initTransaction();
        } catch (BackendException e) {
            logger.error("Failed to init backend: {}", e.getMessage());
        }
    }

    private void initTransaction() throws BackendException {
        this.storeProvider = BackendProviderFactory.open(
                this.configuration.get(ConfigSpace.BACKEND));

        this.schemaTransaction = this.openSchemaTransaction();
        this.graphTransaction = this.openGraphTransaction();

        this.schemaTransaction.autoCommit(true);
        this.graphTransaction.autoCommit(true);
    }

    public void initBackend() {
        this.storeProvider.init();
    }

    public void clearBackend() {
        this.storeProvider.clear();
    }

    private SchemaTransaction openSchemaTransaction() {
        try {
            String name = this.configuration.get(ConfigSpace.STORE_SCHEMA);
            BackendStore store = this.storeProvider.loadSchemaStore(name);
            store.open(this.configuration);
            return new SchemaTransaction(this, store);
        } catch (BackendException e) {
            String message = "Failed to open schema transaction";
            logger.error("{}: {}", message, e.getMessage());
            throw new HugeException(message);
        }
    }

    private GraphTransaction openGraphTransaction() {
        try {
            String name = this.configuration.get(ConfigSpace.STORE_GRAPH);
            BackendStore store = this.storeProvider.loadGraphStore(name);
            store.open(this.configuration);
            return new GraphTransaction(this, store);
        } catch (BackendException e) {
            String message = "Failed to open graph transaction";
            logger.error("{}: {}", message, e.getMessage());
            throw new HugeException(message);
        }
    }

    public SchemaTransaction schemaTransaction() {
        return this.schemaTransaction;
    }

    public GraphTransaction graphTransaction() {
        return this.graphTransaction;
    }

    public SchemaManager schema() {
        return new HugeSchemaManager(this.schemaTransaction());
    }

    public GraphTransaction openTransaction() {
        return this.openGraphTransaction();
    }

    public AbstractSerializer serializer() {
        // TODO: read from conf
        String name = this.configuration.get(ConfigSpace.SERIALIZER);
        AbstractSerializer serializer = SerializerFactory.serializer(name, this);
        if (serializer == null) {
            throw new HugeException("Can't load serializer with name " + name);
        }
        return serializer;
    }

    @Override
    public Vertex addVertex(Object... keyValues) {
        return this.graphTransaction().addVertex(keyValues);
    }

    @Override
    public <C extends GraphComputer> C compute(Class<C> aClass) throws IllegalArgumentException {
        return null;
    }

    @Override
    public GraphComputer compute() throws IllegalArgumentException {
        return null;
    }

    @Override
    public Iterator<Vertex> vertices(Object... objects) {
        return this.graphTransaction().queryVertices(objects);
    }

    public Iterator<Vertex> vertices(Query query) {
        return this.graphTransaction().queryVertices(query);
    }

    @Override
    public Iterator<Edge> edges(Object... objects) {
        return this.graphTransaction().queryEdges(objects);
    }

    public Iterator<Edge> edges(Query query) {
        return this.graphTransaction().queryEdges(query);
    }

    @Override
    public Transaction tx() {
        return this.tx;
    }

    @Override
    public void close() throws Exception {
        // this.storeProvider.close();
    }

    @Override
    public Features features() {
        return this.features;
    }

    @Override
    public Variables variables() {
        return null;
    }

    @Override
    public Configuration configuration() {
        return null;
    }

    private Transaction tx = new AbstractThreadedTransaction(this) {

        private GraphTransaction backendTx = null;

        @Override
        public void doOpen() {
            this.backendTx = graphTransaction();
            this.backendTx.autoCommit(false);
        }

        @Override
        public void doCommit() {
            this.backendTx.commit();
        }

        @Override
        public void doRollback() {
            this.backendTx.rollback();
        }

        @Override
        public <R> Workload<R> submit(Function<Graph, R> graphRFunction) {
            throw new UnsupportedOperationException(
                    "HugeGraph does not support nested transactions. "
                            + "Call submit on a HugeGraph not an individual transaction.");
        }

        @Override
        public <G extends Graph> G createThreadedTx() {
            throw new UnsupportedOperationException(
                    "HugeGraph does not support nested transactions.");
        }

        @Override
        public boolean isOpen() {
            return this.backendTx != null;
        }

        @Override
        public void doClose() {
            // calling super will clear listeners
            super.doClose();

            this.backendTx.autoCommit(true);
            this.backendTx = null;
        }
    };
}