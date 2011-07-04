package brooklyn.entity.basic

import java.lang.reflect.Field
import java.util.Collection
import java.util.Map
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.entity.Application
import brooklyn.entity.Effector
import brooklyn.entity.Entity
import brooklyn.entity.EntityClass
import brooklyn.entity.Group
import brooklyn.entity.ParameterType
import brooklyn.event.AttributeSensor
import brooklyn.event.EventListener
import brooklyn.event.Sensor
import brooklyn.event.basic.AttributeMap
import brooklyn.event.basic.ConfigKey
import brooklyn.location.Location
import brooklyn.management.ManagementContext
import brooklyn.management.SubscriptionContext
import brooklyn.management.Task
import brooklyn.management.internal.BasicSubscriptionContext
import brooklyn.util.internal.LanguageUtils
import brooklyn.util.task.ExecutionContext
import brooklyn.management.internal.AbstractManagementContext

/**
 * Default {@link Entity} implementation.
 * 
 * Provides several common fields ({@link #name}, {@link #id});
 * a map {@link #config} which contains arbitrary config data;
 * sensors and effectors; policies; managementContext.
 * <p>
 * Fields in config can be accessed (get and set) without referring to config,
 * (through use of propertyMissing). Note that config is typically inherited
 * by children, whereas the fields are not. (Attributes cannot be so accessed,
 * nor are they inherited.)
 *
 * @author alex, aled
 */
public abstract class AbstractEntity implements EntityLocal, GroovyInterceptable {
    private static final Logger log = LoggerFactory.getLogger(AbstractEntity.class)
 
    String id = LanguageUtils.newUid()
    Map<String,Object> presentationAttributes = [:]
    String displayName
    final Collection<Group> groups = new CopyOnWriteArrayList<Group>()
    volatile Application application
    Collection<Location> locations = []
    Entity owner

    // following two perhaps belong in entity class in a registry;
    // but that is an optimization, and possibly wrong if we have dynamic sensors/effectors
    // (added only to this instance), however if we did we'd need to reset/update entity class
    // on sensor/effector set change
    /** map of effectors on this entity by name, populated at constructor time */
    private Map<String,Effector> effectors = null
    /** map of sensors on this entity by name, populated at constructor time */
    private Map<String,Sensor> sensors = null
    
    protected transient ExecutionContext execution
    protected transient SubscriptionContext subscription
    
    final Collection<Entity> ownedChildren = new CopyOnWriteArraySet<Entity>();
 
    /**
     * The sensor-attribute values of this entity. Updating this map should be done
     * via getAttribute/updateAttribute; it will automatically emit an attribute-change event.
     */
    protected final AttributeMap attributesInternal = new AttributeMap(this)
    
    /**
     * For temporary data, e.g. timestamps etc for calculating real attribute values, such as when
     * calculating averages over time etc.
     */
    protected final Map<String,Object> tempWorkings = [:]
    
    /*
     * TODO An alternative implementation approach would be to have:
     *   setOwner(Entity o, Map<ConfigKey,Object> inheritedConfig=[:])
     * The idea is that the owner could in theory decide explicitly what in its config
     * would be shared.
     * I (Aled) am undecided as to whether that would be better...
     */
    /**
     * Map of configuration information that is defined at start-up time for the entity. These
     * configuration parameters are shared and made accessible to the "owned children" of this
     * entity.
     */
    protected final Map<ConfigKey,Object> ownConfig = [:]
    protected final Map<ConfigKey,Object> inheritedConfig = [:]

    public AbstractEntity(Entity owner) {
        this([:], owner)
    }
    
    public AbstractEntity(Map flags=[:], Entity owner=null) {
        this.@skipCustomInvokeMethod.set(true)
        if (flags.owner != null && owner != null && flags.owner != owner) {
            throw new IllegalArgumentException("Multiple owners supplied, ${flags.owner} and $owner")
        }
        Entity suppliedOwner = flags.remove('owner') ?: owner
        Map<ConfigKey,Object> suppliedOwnConfig = flags.remove('config')

        if (suppliedOwnConfig) ownConfig.putAll(suppliedOwnConfig)
        
        // initialize the effectors defined on the class
        // (dynamic effectors could still be added; see #getEffectors
        Map<String,Effector> effectorsT = [:]
        for (Field f in getClass().getFields()) {
            if (Effector.class.isAssignableFrom(f.getType())) {
                Effector eff = f.get(this)
                def overwritten = effectorsT.put(eff.name, eff)
                if (overwritten!=null) log.warn("multiple definitions for effector ${eff.name} on $this; preferring $eff to $overwritten")
            }
        }
        effectors = effectorsT

        Map<String,Sensor> sensorsT = [:]
        for (Field f in getClass().getFields()) {
            if (Sensor.class.isAssignableFrom(f.getType())) {
                Sensor sens = f.get(this)
                def overwritten = sensorsT.put(sens.name, sens)
                if (overwritten!=null) log.warn("multiple definitions for sensor ${sens.name} on $this; preferring $sens to $overwritten")
            }
        }
        sensors = sensorsT

        //set the owner if supplied; accept as argument or field
        if (suppliedOwner) suppliedOwner.addOwnedChild(this)
        this.@skipCustomInvokeMethod.set(false)
    }

    /**
     * Adds this as a member of the given group, registers with application if necessary
     */
    public synchronized void setOwner(Entity e) {
        if (owner!=null) {
            if (owner==e) return ;
            if (owner!=e) throw new UnsupportedOperationException("Cannot change owner of $this from $owner to $e (owner change not supported)")
        }
        //make sure there is no loop
        if (this.equals(e)) throw new IllegalStateException("entity $this cannot own itself")
        if (isDescendant(e)) throw new IllegalStateException("loop detected trying to set owner of $this as $e, which is already a decendent")
        
        owner = e
        ((AbstractEntity)e).addOwnedChild(this)
        inheritedConfig.putAll(owner.getAllConfig())
        
        getApplication()
    }

    public boolean isAncestor(Entity oldee) {
        AbstractEntity ancestor = getOwner()
        while (ancestor) {
            if (ancestor.equals(oldee)) return true
            ancestor = ancestor.getOwner()
        }
        return false
    }

    public boolean isDescendant(Entity youngster) {
        Set<Entity> inspected = [] as HashSet
        List<Entity> toinspect = [this]
        
        while (!toinspect.isEmpty()) {
            Entity e = toinspect.pop()
            if (e.getOwnedChildren().contains(youngster)) {
                return true
            }
            inspected.add(e)
            toinspect.addAll(e.getOwnedChildren())
            toinspect.removeAll(inspected)
        }
        
        return false
    }

    /**
     * Adds the given entity as a member of this group <em>and</em> this group as one of the groups of the child;
     * returns argument passed in, for convenience.
     */
    public Entity addOwnedChild(Entity child) {
        if (isAncestor(child)) throw new IllegalStateException("loop detected trying to add child $child to $this; it is already an ancestor")
        child.setOwner(this)
        ownedChildren.add(child)
        child
    }
 
    public boolean removeOwnedChild(Entity child) {
        ownedChildren.remove child
        child.setOwner(null)
    }
    
    /**
     * Adds this as a member of the given group, registers with application if necessary
     */
    public void addGroup(Group e) {
        groups.add e
        getApplication()
    }
 
    public Entity getOwner() { owner }

    public Collection<Group> getGroups() { groups }

    /**
     * Returns the application, looking it up if not yet known (registering if necessary)
     */
    public Application getApplication() {
        if (this.@application!=null) return this.@application;
        def app = owner?.getApplication()
        if (app) {
            registerWithApplication(app)
            this.@application
        }
        app
    }

    public String getApplicationId() {
        getApplication()?.id
    }

    public ManagementContext getManagementContext() {
        getApplication()?.getManagementContext()
    }
    
    protected synchronized void registerWithApplication(Application app) {
        if (application) return;
        this.application = app
        app.registerEntity(this)
    }

    private transient EntityClass entityClass = null
    public synchronized EntityClass getEntityClass() {
        if (!entityClass) return entityClass
        entityClass = new BasicEntityClass(getClass().getCanonicalName(), getSensors().values(), getEffectors().values())
    }

    /**
     * Should be invoked at end-of-life to clean up the item.
     */
    public void destroy() {
        //FIXME this doesn't exist, but we need some way of deleting stale items
        removeApplicationRegistrant()
    }

    public <T> T getAttribute(AttributeSensor<T> attribute) {
        attributesInternal.getValue(attribute);
    }
 
    public <T> T updateAttribute(AttributeSensor<T> attribute, T val) {
        log.info "updating attribute {} as {}", attribute.name, val
        attributesInternal.update(attribute, val);
    }

    @Override
    public <T> T getConfig(ConfigKey<T> key) {
        // FIXME What about inherited task in config?!
        Object v = ownConfig.get(key);
        v = v ?: inheritedConfig.get(key)

        //if config is set as a task, we wait for the task to complete
        while (v in Task) { v = v.get() }
        v
    }

    @Override
    public <T> T setConfig(ConfigKey<T> key, T val) {
        // TODO Is this the best idea, for making life easier for brooklyn coders when supporting changing config?
        if (application?.isDeployed()) throw new IllegalStateException("Cannot set configuration $key on active entity $this")
        
        T oldVal = ownConfig.put(key, val);
        if ((val in Task) && (!(val.isSubmitted()))) {
            //if config is set as a task, we make sure it starts running
            getExecutionContext().submit(val)
        }
        
        ownedChildren.each {
            it.refreshInheritedConfig()
        }
        
        oldVal
    }

    public void refreshInheritedConfig() {
        if (owner != null) {
            inheritedConfig.putAll(owner.getAllConfig())
        } else {
            inheritedConfig.clear();
        }
        
        ownedChildren.each {
            it.refreshInheritedConfig()
        }
    }
    
    @Override
    public Map<ConfigKey,Object> getAllConfig() {
        // FIXME What about task-based config?!
        Map<ConfigKey,Object> result = [:]
        result.putAll(ownConfig);
        result.putAll(inheritedConfig);
        return result.asImmutable()
    }

    /** @see Entity#subscribe(Entity, Sensor, EventListener) */
    public <T> long subscribe(Entity producer, Sensor<T> sensor, EventListener<T> listener) {
        subscriptionContext.getSubscriptionManager().subscribe this.id, producer.id, sensor.name, listener
    }

    protected synchronized SubscriptionContext getSubscriptionContext() {
        if (subscription) subscription;
        subscription = getManagementContext()?.getSubscriptionContext(this);
    }

    protected synchronized ExecutionContext getExecutionContext() {
        if (execution) execution;
        execution = new ExecutionContext(tag: this, getManagementContext().getExecutionManager())
    }
    
    /** default toString is simplified name of class, together with selected arguments */
    @Override
    public String toString() {
        StringBuffer result = []
        result << getClass().getSimpleName()
        if (!result) result << getClass().getName()
        result << "[" << toStringFieldsToInclude().collect({
            def v = this.hasProperty(it) ? this[it] : null  /* TODO would like to use attributes, config: this.properties[it] */
            v ? "$it=$v" : null
        }).findAll({it!=null}).join(",") << "]"
    }
 
    /** override this, adding to the collection, to supply fields whose value, if not null, should be included in the toString */
    public Collection<String> toStringFieldsToInclude() { ['id', 'displayName'] }

    // -------- SENSORS --------------------
    
    /** @see EntityLocal#emit(Sensor, Object) */
    public <T> void emit(Sensor<T> sensor, T val) {
        subscriptionContext?.publish(sensor.newEvent(this, val))
    }

    /** sensors available on this entity
     * <p>
     * NB no work has been done supporting changing this after initialization; see note on {@link #getEffectors()}
     */
    public Map<String,Sensor<?>> getSensors() { sensors }
    /** convenience for finding named sensor in {@link #getSensor()} map */
    public <T> Sensor<T> getSensor(String sensorName) { getSensors()[sensorName] }

    // -------- EFFECTORS --------------

    /** flag needed internally to prevent invokeMethod from recursing on itself */     
    private ThreadLocal<Boolean> skipCustomInvokeMethod = new ThreadLocal() { protected Object initialValue() { Boolean.FALSE } }

    public Object invokeMethod(String name, Object args) {
        if (!this.@skipCustomInvokeMethod.get()) {
            this.@skipCustomInvokeMethod.set(true);
            
            //args should be an array, warn if we got here wrongly (extra defensive as args accepts it, but it shouldn't happen here)
            if (args==null) log.warn("$this.$name invoked with incorrect args signature (null)", new Throwable("source of incorrect invocation of $this.$name"))
            else if (!args.getClass().isArray()) log.warn("$this.$name invoked with incorrect args signature (non-array ${args.getClass()}): "+args, new Throwable("source of incorrect invocation of $this.$name"))
            
            try {
                Effector eff = getEffectors().get(name)
                if (eff) {
                    args = AbstractEffector.prepareArgsForEffector(eff, args);
                    Task currentTask = ExecutionContext.getCurrentTask();
                    if (!currentTask || !currentTask.getTags().contains(this)) {
                        //wrap in a task if we aren't already in a task that is tagged with this entity
                        MetaClass mc = metaClass
                        Task t = executionContext.submit( { mc.invokeMethod(this, name, args); },
                            description: "call to method $name being treated as call to effector $eff" )
                        return t.get();
                    }
                }
            } finally { this.@skipCustomInvokeMethod.set(false); }
        }
        metaClass.invokeMethod(this, name, args);
        //following is recommended on web site, but above is how groovy actually implements it
//            def metaMethod = metaClass.getMetaMethod(name, newArgs)
//            if (metaMethod==null)
//                throw new IllegalArgumentException("Invalid arguments (no method found) for method $name: "+newArgs);
//            metaMethod.invoke(this, newArgs)
    }
    
    /** effectors available on this entity
     * <p>
     * NB no work has been done supporting changing this after initialization,
     * but the idea of these so-called "dynamic effectors" has been discussed and it might be supported in future...
     */
    public Map<String,Effector> getEffectors() { effectors }
    /** convenience for finding named effector in {@link #getEffectors()} map */
    public <T> Effector<T> getEffector(String effectorName) { getEffectors()[effectorName] }
    
    public <T> Task<T> invoke(Map parameters=[:], Effector<T> eff) {
        invoke(eff, parameters);
    }
 
    //add'l form supplied for when map needs to be made explicit (above supports implicit named args)
    public <T> Task<T> invoke(Effector<T> eff, Map parameters) {
        executionContext.submit( { eff.call(this, parameters) }, description: "invocation of effector $eff" )
    }
}
