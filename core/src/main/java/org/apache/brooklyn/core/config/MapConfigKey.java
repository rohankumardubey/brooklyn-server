/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.core.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import org.apache.brooklyn.api.mgmt.ExecutionContext;
import org.apache.brooklyn.api.mgmt.TaskAdaptable;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.internal.AbstractStructuredConfigKey;
import org.apache.brooklyn.util.collections.Jsonya;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.flags.TypeCoercions;
import org.apache.brooklyn.util.core.task.ValueResolver;
import org.apache.brooklyn.util.guava.Maybe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Supplier;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;

/** A config key which represents a map, where contents can be accessed directly via subkeys.
 * Items added directly to the map must be of type map, and can be updated by:
 * <ul>
 * <li>Putting individual subkeys ({@link SubElementConfigKey})
 * <li>Passing an an appropriate {@link MapModification} from {@link MapModifications}
 *      to clear, clear-and-set, or update
 * <li>Setting a value against a dot-extension of the key
 *     (e.g. setting <code>a.map.subkey=1</code> will cause getConfig(a.map[type=MapConfigKey])
 *     to return {subkey=1}; but note the above are preferred where possible)  
 * <li>Setting a map directly against the MapConfigKey (but note that the above are preferred where possible)
 * </ul>
 */
//TODO Create interface
//TODO The BasicConfigKey.builder(Class,name) methods mean we can't define them on this sub-type,
//     because we want builder(Class<V>), which corresponds to BasicConfigKey<Map<String, V>.
public class MapConfigKey<V> extends AbstractStructuredConfigKey<Map<String,V>,Map<String,Object>,V> {
    
    private static final long serialVersionUID = -6126481503795562602L;
    private static final Logger log = LoggerFactory.getLogger(MapConfigKey.class);

    public static <V> Builder<V> builder(MapConfigKey<V> key) {
        return new Builder<V>(key);
    }

    @SuppressWarnings("serial")
    private static <V> TypeToken<Map<String,V>> typeTokenFor(TypeToken<V> subType) {
        return new TypeToken<Map<String,V>>() {}
                 .where(new TypeParameter<V>() {}, subType);
    }
    
    public static class Builder<V> extends BasicConfigKey.Builder<Map<String, V>,Builder<V>> {
        protected TypeToken<V> subType;
        
        public Builder(TypeToken<V> subType, String name) {
            super(typeTokenFor(subType), name);
            this.subType = subType;
        }
        public Builder(Class<V> subType, String name) {
            this(TypeToken.of(subType), name);
        }
        public Builder(MapConfigKey<V> key) {
            this(key.getName(), key);
        }
        public Builder(String newName, MapConfigKey<V> key) {
            super(newName, key);
            subType = key.getSubTypeToken();
        }
        @Override
        public Builder<V> self() {
            return this;
        }
        @Override
        @Deprecated
        public Builder<V> name(String val) {
            throw new UnsupportedOperationException("Builder must be constructed with name");
        }
        @Override
        @Deprecated
        public Builder<V> type(Class<Map<String, V>> val) {
            throw new UnsupportedOperationException("Builder must be constructed with type");
        }
        @Override
        @Deprecated
        public Builder<V> type(TypeToken<Map<String, V>> val) {
            throw new UnsupportedOperationException("Builder must be constructed with type");
        }
        @Override
        public MapConfigKey<V> build() {
            return new MapConfigKey<V>(this);
        }
    }

    protected MapConfigKey(Builder<V> builder) {
        super(builder, builder.subType);
    }

    public MapConfigKey(TypeToken<V> subType, String name) {
        this(subType, name, name, null);
    }

    public MapConfigKey(TypeToken<V> subType, String name, String description) {
        this(subType, name, description, null);
    }

    // TODO it isn't clear whether defaultValue is an initialValue, or a value to use when map is empty
    // probably the latter, currently ... but maybe better to say that map configs are never null, 
    // and defaultValue is really an initial value?
    public MapConfigKey(TypeToken<V> subType, String name, String description, Map<String, V> defaultValue) {
        super(typeTokenFor(subType), subType, name, description, defaultValue);
    }

    public MapConfigKey(Class<V> subType, String name) {
        this(TypeToken.of(subType), name);
    }

    public MapConfigKey(Class<V> subType, String name, String description) {
        this(TypeToken.of(subType), name, description);
    }

    public MapConfigKey(Class<V> subType, String name, String description, Map<String, V> defaultValue) {
        this(TypeToken.of(subType), name, description, defaultValue);
    }
    
    @Override
    public String toString() {
        return String.format("%s[MapConfigKey:%s]", name, getTypeName());
    }

    @Override
    public ConfigKey<V> subKey(String subName) {
        return super.subKey(subName);
    }
    @Override
    public ConfigKey<V> subKey(String subName, String description) {
        return super.subKey(subName, description);
    }   

    @SuppressWarnings("unchecked")
    @Override
    protected Map<String, Object> extractValueMatchingThisKey(Object potentialBase, ExecutionContext exec, boolean coerce) throws InterruptedException, ExecutionException {
        if (coerce) {
            potentialBase = resolveValue(potentialBase, exec);
        }

        if (potentialBase==null) return null;
        if (potentialBase instanceof Map<?,?>) {
            return Maps.<String,Object>newLinkedHashMap( (Map<String,Object>) potentialBase);
        }
        log.warn("Unable to extract "+getName()+" as Map; it is "+potentialBase.getClass().getName()+" "+potentialBase);
        return null;
    }
    
    @Override
    protected Map<String, Object> merge(Map<String, Object> base, Map<String, Object> subkeys, boolean unmodifiable) {
        Map<String, Object> result = MutableMap.copyOf(base).add(subkeys);
        if (unmodifiable) result = Collections.unmodifiableMap(result);
        return result;
    }
    
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Override
    public Object applyValueToMap(Object value, Map target) {
        if (value == null) {
            return null;
        }
        if (value instanceof StructuredModification) {
            return ((StructuredModification) value).applyToKeyInMap(this, target);
        }
        if (value instanceof Map.Entry) {
            return applyEntryValueToMap((Map.Entry) value, target);
        }
        if (!(value instanceof Map)) {
            if (ValueResolver.isDeferredOrTaskInternal(value)) {
                boolean isSet = isSet(target);
                if (isSet) {
                    String warning = "Discouraged undecorated setting of a task to in-use StructuredConfigKey " + this + ": use MapModification.{set,add}. " +
                            "Defaulting to replacing root. Look at debug logging for call stack.";
                    log.warn(warning);
                    if (log.isDebugEnabled())
                        log.debug("Trace for: " + warning, new Throwable("Trace for: " + warning));
                }
                // just put here as the set - prior to 2021-05 we put things under an anonymous subkey
                return target.put(this, value);
            }

            Maybe<Map> coercedValue = TypeCoercions.tryCoerce(value, Map.class);
            if (coercedValue.isPresent()) {
                log.trace("Coerced value for {} from type {} to map", this, value.getClass().getName());
                value = coercedValue.get();
            } else {
                throw new IllegalArgumentException("Cannot set non-map entries on "+this+", given type "+value.getClass().getName()+", value "+value);
            }
        }

        Map result = new MutableMap();
        for (Object entry: ((Map)value).entrySet()) {
            Map.Entry entryT = (Map.Entry)entry;
            result.put(entryT.getKey(), applyEntryValueToMap(entryT, target));
        }
        if (((Map)value).isEmpty() && !isSet(target))
            target.put(this, MutableMap.of());
        return result;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected Object applyEntryValueToMap(Entry value, Map target) {
        Object k = value.getKey();
        if (acceptsSubkeyStronglyTyped(k)) {
            // do nothing
        } else if (k instanceof ConfigKey<?>) {
            k = subKey( ((ConfigKey<?>)k).getName() );
        } else if (k instanceof String) {
            k = subKey((String)k);
        } else {
            // supplier or other unexpected value
            if (k instanceof Supplier) {
                Object mapAtRoot = target.get(this);
                if (mapAtRoot==null) {
                    mapAtRoot = new LinkedHashMap();
                    target.put(this, mapAtRoot);
                }
                // TODO above is not thread-safe, and below is assuming synching on map 
                // is the best way to prevent CME's, which is often but not always true
                if (mapAtRoot instanceof Map) {
                    if (mapAtRoot instanceof ConcurrentMap) {
                        return ((Map)mapAtRoot).put(k, value.getValue());
                    } else {
                        synchronized (mapAtRoot) {
                            return ((Map)mapAtRoot).put(k, value.getValue());
                        }
                    }
                }
            }
            log.warn("Unexpected subkey "+k+" being inserted into "+this+"; ignoring");
            k = null;
        }
        if (k!=null)
            return target.put(k, value.getValue());
        else 
            return null;
    }

    public interface MapModification<V> extends StructuredModification<MapConfigKey<V>>, Map<String,V> {
    }
    
    public static class MapModifications extends StructuredModifications {
        /** when passed as a value to a MapConfigKey, causes each of these items to be put 
         * (this Mod is redundant as no other value is really sensible) */
        public static final <V> MapModification<V> put(final Map<String,V> itemsToPutInMapReplacing) { 
            return new MapModificationBase<V>(itemsToPutInMapReplacing, false);
        }
        /** when passed as a value to a MapConfigKey, causes the map to be cleared and these items added */
        public static final <V> MapModification<V> set(final Map<String,V> itemsToPutInMapAfterClearing) {
            return new MapModificationBase<V>(itemsToPutInMapAfterClearing, true);
        }
        /** when passed as a value to a MapConfigKey, causes the items to be added to the underlying map
         * using {@link Jsonya} add semantics (combining maps and lists) */
        public static final <V> MapModification<V> add(final Map<String,V> itemsToAdd) {
            return new MapModificationBase<V>(itemsToAdd, false /* ignored */) {
                private static final long serialVersionUID = 1L;
                @SuppressWarnings("rawtypes")
                @Override
                public Object applyToKeyInMap(MapConfigKey<V> key, Map target) {
                    return key.applyValueToMap(Jsonya.of(key.rawValue(target)).add(this).getRootMap(), target);
                }
            };
        }
    }

    public static class MapModificationBase<V> extends LinkedHashMap<String,V> implements MapModification<V> {
        private static final long serialVersionUID = -1670820613292286486L;
        private final boolean clearFirst;
        public MapModificationBase(Map<String,V> delegate, boolean clearFirst) {
            super(delegate);
            this.clearFirst = clearFirst;
        }
        @SuppressWarnings({ "rawtypes" })
        @Override
        public Object applyToKeyInMap(MapConfigKey<V> key, Map target) {
            if (clearFirst) {
                StructuredModification<StructuredConfigKey> clearing = StructuredModifications.clearing();
                clearing.applyToKeyInMap(key, target);
            }
            return key.applyValueToMap(new LinkedHashMap<String,V>(this), target);
        }
    }
}
