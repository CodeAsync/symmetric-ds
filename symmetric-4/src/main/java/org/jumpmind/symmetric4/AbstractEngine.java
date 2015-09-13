package org.jumpmind.symmetric4;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.jumpmind.db.platform.IDatabasePlatform;
import org.jumpmind.db.sql.ISqlTemplate;
import org.jumpmind.properties.TypedProperties;
import org.jumpmind.security.ISecurityService;
import org.jumpmind.security.SecurityServiceFactory;
import org.jumpmind.security.SecurityServiceFactory.SecurityServiceType;
import org.jumpmind.symmetric4.model.ParameterConstants;
import org.jumpmind.symmetric4.model.Version;
import org.jumpmind.symmetric4.service.ConfigurationService;
import org.jumpmind.symmetric4.service.DialectService;
import org.jumpmind.symmetric4.service.ExtensionService;
import org.jumpmind.symmetric4.service.JobService;
import org.jumpmind.symmetric4.task.IScheduler;
import org.jumpmind.symmetric4.task.ITask;
import org.jumpmind.symmetric4.task.manage.SyncTriggersTask;
import org.jumpmind.util.IService;
import org.jumpmind.util.ITypedPropertiesFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public abstract class AbstractEngine implements IEngine, IApplicationContext {

    private static Map<String, IEngine> registeredEnginesByUrl = new HashMap<String, IEngine>();
    private static Map<String, IEngine> registeredEnginesByName = new HashMap<String, IEngine>();

    protected static final Logger log = LoggerFactory.getLogger(AbstractEngine.class);

    private boolean started = false;

    private boolean starting = false;

    private boolean stopping = false;

    private boolean databaseInitialized = false;

    private Date lastRestartTime;

    protected String deploymentType;

    protected ITypedPropertiesFactory propertiesFactory;

    protected IDatabasePlatform databasePlatform;

    protected Map<Class<?>, IService> services = new HashMap<Class<?>, IService>();

    protected Map<Class<?>, ITask> workers = new HashMap<Class<?>, ITask>();

    protected IScheduler scheduler;

    protected AbstractEngine(boolean registerEngine) {
        this.init(registerEngine);
    }

    protected void init(boolean registerEngine) {
        if (propertiesFactory == null) {
            this.propertiesFactory = createTypedPropertiesFactory();
        }

        TypedProperties properties = this.propertiesFactory.reload();

        if (!services.containsKey(ISecurityService.class)) {
            services.put(ISecurityService.class, SecurityServiceFactory.create(getSecurityServiceType(), properties));
        }

        MDC.put("engineName", properties.get(ParameterConstants.ENGINE_NAME));

        String tablePrefix = properties.get(ParameterConstants.TABLE_PREFIX);
        long cacheTimeout = properties.getLong(ParameterConstants.CACHE_TIMEOUT_SECONDS)*1000;

        this.databasePlatform = createDatabasePlatform(properties);
        this.databasePlatform.setMetadataIgnoreCase(properties.is(ParameterConstants.DB_METADATA_IGNORE_CASE));
        this.databasePlatform.setClearCacheModelTimeoutInMs(cacheTimeout);

        initServices(tablePrefix, cacheTimeout);

        initWorkers();

        if (registerEngine) {
            registerEngine();
        }
    }

    public void start() {
        starting = true;

        lastRestartTime = new Date();
        
        DialectService dialectService = getService(DialectService.class);
        ConfigurationService configService = getService(ConfigurationService.class);
        TypedProperties parameters = configService.getParameters();
        
        dialectService.verifyCompatibilty();

        if (!databaseInitialized && parameters.is(ParameterConstants.AUTO_CONFIGURE_DATABASE)) {
            dialectService.initializeDatabase();
            autoConfigRegistrationServer();
            databaseInitialized = true;
        }

        for (ITask worker : workers.values()) {
            worker.start();
        }

        starting = false;
        started = true;
        
        ISqlTemplate sqlTemplate = databasePlatform.getSqlTemplate();
        int databaseMajorVersion = sqlTemplate.getDatabaseMajorVersion();
        int databaseMinorVersion = sqlTemplate.getDatabaseMinorVersion();
        String databaseName = sqlTemplate.getDatabaseProductName();
        String driverName = sqlTemplate.getDriverName();
        String driverVersion = sqlTemplate.getDriverVersion();
        
        log.info(
                "SymmetricDS: name={}, version={}, groupId={}, externalId={}, databaseName={}, databaseVersion={}, driverName={}, driverVersion={}",
                new Object[] { parameters.get(ParameterConstants.ENGINE_NAME), Version.version(),
                        parameters.get(ParameterConstants.NODE_GROUP_ID),
                        parameters.get(ParameterConstants.EXTERNAL_ID), databaseName,
                        databaseMajorVersion + "." + databaseMinorVersion, driverName,
                        driverVersion });

        if (parameters.is(ParameterConstants.AUTO_SYNC_TRIGGERS_AT_STARTUP)) {
            getWorker(SyncTriggersTask.class).scheduleNow();
        }
    }

    public void stop() {
        if (started) {
            stopping = true;
            for (ITask worker : workers.values()) {
                worker.stop();
            }
            stopping = false;
            started = false;
        }
    }
    
    protected void autoConfigRegistrationServer() {
        
    }

    protected void initServices(String tablePrefix, long cacheTimeout) {
        services.put(ExtensionService.class, createExtensionService(tablePrefix, cacheTimeout));
        services.put(ConfigurationService.class, new ConfigurationService(propertiesFactory, tablePrefix, cacheTimeout, databasePlatform));
        services.put(JobService.class, new JobService(tablePrefix, cacheTimeout, databasePlatform));
        services.put(DialectService.class, createDialectService(tablePrefix, cacheTimeout));
    }

    protected void initWorkers() {

    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getService(Class<T> clazz) {
        return (T) this.services.get(clazz);
    }

    @Override
    public <T> T getWorker(Class<T> clazz) {
        return (T) getWorker(clazz);
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isStarting() {
        return starting;
    }

    public boolean isStopping() {
        return stopping;
    }

    abstract protected DialectService createDialectService(String tablePrefix, long cacheTimeout);

    abstract protected IScheduler createScheduler();

    abstract protected SecurityServiceType getSecurityServiceType();

    abstract protected ITypedPropertiesFactory createTypedPropertiesFactory();

    abstract protected IDatabasePlatform createDatabasePlatform(TypedProperties properties);

    abstract protected DialectService createDialectService();

    protected ExtensionService createExtensionService(String tablePrefix, long cacheTimeout) {
        return new ExtensionService(tablePrefix, cacheTimeout, databasePlatform);
    }

    /**
     * Register this instance of the engine so it can be found by other
     * processes in the JVM.
     * 
     * @see #findEngineByUrl(String)
     */
    private void registerEngine() {
        // String url = getSyncUrl();
        // IEngine alreadyRegister = registeredEnginesByUrl.get(url);
        // if (alreadyRegister == null || alreadyRegister.equals(this)) {
        // if (url != null) {
        // registeredEnginesByUrl.put(url, this);
        // }
        // } else {
        // log.warn("Could not register engine. There was already an engine
        // registered under the url: {}", getSyncUrl());
        // }
        //
        // alreadyRegister = registeredEnginesByName.get(getEngineName());
        // if (alreadyRegister == null || alreadyRegister.equals(this)) {
        // registeredEnginesByName.put(getEngineName(), this);
        // } else {
        // throw new EngineAlreadyRegisteredException(
        // "Could not register engine. There was already an engine registered
        // under the name: " + getEngineName());
        // }

    }

    public Date getLastRestartTime() {
        return lastRestartTime;
    }

    @SuppressWarnings("unchecked")
    public <T> T getDataSource() {
        return (T) databasePlatform.getDataSource();
    }

    public IDatabasePlatform getDatabasePlatform() {
        return databasePlatform;
    }

    /**
     * Locate a {@link StandaloneSymmetricEngine} in the same JVM
     */
    public static IEngine findEngineByUrl(String url) {
        if (registeredEnginesByUrl != null && url != null) {
            return registeredEnginesByUrl.get(url);
        } else {
            return null;
        }
    }

    /**
     * Locate a {@link StandaloneSymmetricEngine} in the same JVM
     */
    public static IEngine findEngineByName(String name) {
        if (registeredEnginesByName != null && name != null) {
            return registeredEnginesByName.get(name);
        } else {
            return null;
        }
    }

}
