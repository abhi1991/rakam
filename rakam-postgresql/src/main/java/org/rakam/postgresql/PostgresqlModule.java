package org.rakam.postgresql;

import com.google.auto.service.AutoService;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.Scopes;
import com.google.inject.name.Names;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import org.rakam.analysis.ApiKeyService;
import org.rakam.analysis.ConfigManager;
import org.rakam.analysis.ContinuousQueryService;
import org.rakam.analysis.EscapeIdentifier;
import org.rakam.analysis.EventExplorer;
import org.rakam.analysis.FunnelQueryExecutor;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.analysis.MaterializedViewService;
import org.rakam.analysis.RetentionQueryExecutor;
import org.rakam.analysis.TimestampToEpochFunction;
import org.rakam.analysis.metadata.JDBCQueryMetadata;
import org.rakam.analysis.metadata.Metastore;
import org.rakam.analysis.metadata.QueryMetadataStore;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.config.JDBCConfig;
import org.rakam.config.MetadataConfig;
import org.rakam.config.ProjectConfig;
import org.rakam.plugin.EventStore;
import org.rakam.plugin.RakamModule;
import org.rakam.plugin.SystemEvents;
import org.rakam.plugin.user.AbstractUserService;
import org.rakam.plugin.user.UserPluginConfig;
import org.rakam.postgresql.analysis.FastGenericFunnelQueryExecutor;
import org.rakam.postgresql.analysis.PostgresqlConfig;
import org.rakam.postgresql.analysis.PostgresqlEventStore;
import org.rakam.postgresql.analysis.PostgresqlFunnelQueryExecutor;
import org.rakam.postgresql.analysis.PostgresqlMaterializedViewService;
import org.rakam.postgresql.analysis.PostgresqlMetastore;
import org.rakam.postgresql.analysis.PostgresqlRetentionQueryExecutor;
import org.rakam.postgresql.plugin.user.AbstractPostgresqlUserStorage;
import org.rakam.postgresql.plugin.user.PostgresqlUserService;
import org.rakam.postgresql.plugin.user.PostgresqlUserStorage;
import org.rakam.postgresql.report.PostgresqlEventExplorer;
import org.rakam.postgresql.report.PostgresqlPseudoContinuousQueryService;
import org.rakam.postgresql.report.PostgresqlQueryExecutor;
import org.rakam.report.QueryExecutor;
import org.rakam.report.eventexplorer.EventExplorerConfig;
import org.rakam.util.ConditionalModule;

import javax.inject.Inject;

import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;

import static org.rakam.util.ValidationUtil.checkCollection;
import static org.rakam.util.ValidationUtil.checkTableColumn;

@AutoService(RakamModule.class)
@ConditionalModule(config = "store.adapter", value = "postgresql")
public class PostgresqlModule
        extends RakamModule
{
    @Override
    protected void setup(Binder binder)
    {
        JDBCConfig config = buildConfigObject(JDBCConfig.class, "store.adapter.postgresql");
        PostgresqlConfig postgresqlConfig = buildConfigObject(PostgresqlConfig.class);
        MetadataConfig metadataConfig = buildConfigObject(MetadataConfig.class);

        JDBCPoolDataSource orCreateDataSource = JDBCPoolDataSource.getOrCreateDataSource(config, "set time zone 'UTC'");
        binder.bind(JDBCPoolDataSource.class)
                .annotatedWith(Names.named("store.adapter.postgresql"))
                .toInstance(orCreateDataSource);

        binder.bind(char.class).annotatedWith(EscapeIdentifier.class).toInstance('"');

        binder.bind(Metastore.class).to(PostgresqlMetastore.class).asEagerSingleton();
        binder.bind(ApiKeyService.class).toInstance(new PostgresqlApiKeyService(orCreateDataSource));

        binder.bind(MaterializedViewService.class).to(PostgresqlMaterializedViewService.class).in(Scopes.SINGLETON);
        binder.bind(QueryExecutor.class).to(PostgresqlQueryExecutor.class).in(Scopes.SINGLETON);
        binder.bind(ContinuousQueryService.class).to(PostgresqlPseudoContinuousQueryService.class).in(Scopes.SINGLETON);
        binder.bind(String.class).annotatedWith(TimestampToEpochFunction.class).toInstance("to_unixtime");

        binder.bind(boolean.class).annotatedWith(Names.named("user.storage.postgresql"))
                .toInstance("postgresql".equals(getConfig("plugin.user.storage")));

        if (metadataConfig.getEventStore() == null) {
            binder.bind(EventStore.class).to(PostgresqlEventStore.class).in(Scopes.SINGLETON);
        }

        // use same jdbc pool if report.metadata.store is not set explicitly.
        if (getConfig("report.metadata.store") == null) {
            binder.bind(JDBCPoolDataSource.class)
                    .annotatedWith(Names.named("report.metadata.store.jdbc"))
                    .toInstance(orCreateDataSource);

            binder.bind(ConfigManager.class).to(PostgresqlConfigManager.class);
            binder.bind(QueryMetadataStore.class).to(JDBCQueryMetadata.class)
                    .in(Scopes.SINGLETON);
        }

        if (buildConfigObject(EventExplorerConfig.class).isEventExplorerEnabled()) {
            binder.bind(EventExplorer.class).to(PostgresqlEventExplorer.class);
        }

        if (postgresqlConfig.isAutoIndexColumns()) {
            binder.bind(CollectionFieldIndexerListener.class).asEagerSingleton();
        }

        if ("postgresql".equals(getConfig("plugin.user.storage"))) {
            binder.bind(AbstractUserService.class).to(PostgresqlUserService.class)
                    .in(Scopes.SINGLETON);
            binder.bind(AbstractPostgresqlUserStorage.class).to(PostgresqlUserStorage.class)
                    .in(Scopes.SINGLETON);
        }

        UserPluginConfig userPluginConfig = buildConfigObject(UserPluginConfig.class);

        if (userPluginConfig.isFunnelAnalysisEnabled()) {
            binder.bind(FunnelQueryExecutor.class).to(PostgresqlFunnelQueryExecutor.class);
        }

        if (userPluginConfig.isRetentionAnalysisEnabled()) {
            binder.bind(RetentionQueryExecutor.class).to(PostgresqlRetentionQueryExecutor.class);
        }
    }

    @Override
    public String name()
    {
        return "Postgresql Module";
    }

    @Override
    public String description()
    {
        return "Postgresql deployment type module";
    }

    public synchronized static Module getAsyncClientModule(JDBCConfig config)
    {
        JDBCConfig asyncClientConfig;
        try {
            final String url = config.getUrl();

            asyncClientConfig = new JDBCConfig()
                    .setPassword(config.getPassword())
                    .setTable(config.getTable())
                    .setMaxConnection(4)
                    .setConnectionMaxLifeTime(0L)
                    .setConnectionIdleTimeout(0L)
                    .setConnectionDisablePool(config.getConnectionDisablePool())
                    .setUrl("jdbc:pgsql" + url.substring("jdbc:postgresql".length()))
                    .setUsername(config.getUsername());
        }
        catch (URISyntaxException e) {
            throw Throwables.propagate(e);
        }

        return new AbstractConfigurationAwareModule()
        {
            @Override
            protected void setup(Binder binder)
            {
                binder.bind(JDBCPoolDataSource.class)
                        .annotatedWith(Names.named("async-postgresql"))
                        .toProvider(new JDBCPoolDataSourceProvider(asyncClientConfig))
                        .in(Scopes.SINGLETON);
            }
        };
    }

    private static class JDBCPoolDataSourceProvider
            implements Provider<JDBCPoolDataSource>
    {
        private final JDBCConfig asyncClientConfig;

        public JDBCPoolDataSourceProvider(JDBCConfig asyncClientConfig)
        {
            this.asyncClientConfig = asyncClientConfig;
        }

        @Override
        public JDBCPoolDataSource get()
        {
            return JDBCPoolDataSource.getOrCreateDataSource(asyncClientConfig);
        }
    }

    private static class CollectionFieldIndexerListener
    {
        private final PostgresqlQueryExecutor executor;
        private final ProjectConfig projectConfig;
        boolean postgresql9_5;

        @Inject
        public CollectionFieldIndexerListener(ProjectConfig projectConfig, PostgresqlQueryExecutor executor)
        {
            this.executor = executor;
            this.projectConfig = projectConfig;
            try {
                String version = executor.executeRawQuery("SHOW server_version")
                        .getResult().join().getResult().get(0).get(0).toString();
                String[] split = version.split("\\.", 2);
                // Postgresql BRIN support came in 9.5 version
                postgresql9_5 = Integer.parseInt(split[0]) > 9 || (Integer.parseInt(split[0]) == 9 && Double.parseDouble(split[1]) >= 5);
            }
            catch (Exception e) {
                postgresql9_5 = false;
            }
        }

        @Subscribe
        public void onCreateCollection(SystemEvents.CollectionCreatedEvent event)
        {
            onCreateCollectionFields(event.project, event.collection, event.fields);
        }

        @Subscribe
        public void onCreateCollectionFields(SystemEvents.CollectionFieldCreatedEvent event)
        {
            onCreateCollectionFields(event.project, event.collection, event.fields);
        }

        public void onCreateCollectionFields(String project, String collection, List<SchemaField> fields)
        {
            for (SchemaField field : fields) {
                try {
                    // We cant't use CONCURRENTLY because it causes dead-lock with ALTER TABLE and it's slow.
                    executor.executeRawStatement(String.format("CREATE INDEX %s %s ON %s.%s USING %s(%s)",
                            postgresql9_5 ? "IF NOT EXISTS" : "",
                            checkCollection(String.format("%s_%s_%s_auto_index", project, collection, field.getName())),
                            project, checkCollection(collection),
//                            (postgresql9_5 && brinSupportedTypes.contains(field.getType())) ? "BRIN" : "BTREE",
                            (postgresql9_5 && field.getName().equals(projectConfig.getTimeColumn())) ? "BRIN" : "BTREE",
                            checkTableColumn(field.getName())));
                }
                catch (Exception e) {
                    if (postgresql9_5) {
                        throw e;
                    }
                }
            }
        }

        private Set<FieldType> brinSupportedTypes = ImmutableSet.of(FieldType.DATE, FieldType.DECIMAL,
                FieldType.DOUBLE, FieldType.INTEGER, FieldType.LONG,
                FieldType.STRING, FieldType.TIMESTAMP, FieldType.TIME);
    }
}
