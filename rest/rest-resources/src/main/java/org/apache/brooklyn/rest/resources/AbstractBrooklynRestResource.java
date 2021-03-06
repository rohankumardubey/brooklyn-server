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
package org.apache.brooklyn.rest.resources;

import javax.annotation.Nullable;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.ContextResolver;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.BrooklynDslDeferredSupplier;
import org.apache.brooklyn.core.config.Sanitizer;
import org.apache.brooklyn.core.config.render.RendererHints;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.rest.domain.ApiError;
import org.apache.brooklyn.rest.util.BrooklynRestResourceUtils;
import org.apache.brooklyn.rest.util.DefaultExceptionMapper;
import org.apache.brooklyn.rest.util.ManagementContextProvider;
import org.apache.brooklyn.rest.util.WebResourceUtils;
import org.apache.brooklyn.rest.util.json.BrooklynJacksonJsonProvider;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

public abstract class AbstractBrooklynRestResource {

    protected @Context UriInfo ui;

    @Context
    private ContextResolver<ManagementContext> mgmt;

    private BrooklynRestResourceUtils brooklynRestResourceUtils;
    private ObjectMapper mapper;

    public ManagementContext mgmt() {
        return Preconditions.checkNotNull(mgmt.getContext(ManagementContext.class), "mgmt");
    }

    public ManagementContextInternal mgmtInternal() {
        return (ManagementContextInternal) mgmt();
    }

    protected synchronized Maybe<ManagementContext> mgmtMaybe() {
        return Maybe.of(mgmt());
    }

    @VisibleForTesting
    public void setManagementContext(ManagementContext managementContext) {
        this.mgmt = new ManagementContextProvider(managementContext);
    }

    public synchronized BrooklynRestResourceUtils brooklyn() {
        if (brooklynRestResourceUtils!=null) return brooklynRestResourceUtils;
        brooklynRestResourceUtils = new BrooklynRestResourceUtils(mgmt());
        return brooklynRestResourceUtils;
    }
    
    /** returns a bad request Response wrapping the given exception */
    protected Response badRequest(Exception e) {
        DefaultExceptionMapper.logExceptionDetailsForDebugging(e);
        return ApiError.of(e).asBadRequestResponseJson();
    }
    
    protected ObjectMapper mapper() {
        return mapper(mgmt());
    }

    protected ObjectMapper mapper(ManagementContext mgmt) {
        if (mapper==null)
            mapper = BrooklynJacksonJsonProvider.findAnyObjectMapper(mgmt);
        return mapper;
    }

    protected RestValueResolver resolving(Object v) {
        return resolving(v, mgmt());
    }

    protected RestValueResolver resolving(Object v, ManagementContext mgmt) {
        return new RestValueResolver(v).mapper(mapper(mgmt));
    }

    public static class RestValueResolver {
        final private Object valueToResolve;
        private @Nullable ObjectMapper mapper;
        private boolean preferJson;
        private boolean isJerseyReturnValue;

        private @Nullable Entity entity;
        private @Nullable Duration timeout;
        private @Nullable Object rendererHintSource;
        private @Nullable Boolean immediately;

        private @Nullable Boolean raw;
        private @Nullable Boolean useDisplayHints;
        private @Nullable Boolean skipResolution;
        private @Nullable Boolean suppressBecauseSecret;

        public static RestValueResolver resolving(Object v) { return new RestValueResolver(v); }
        
        private RestValueResolver(Object v) { valueToResolve = v; }
        
        public RestValueResolver mapper(ObjectMapper mapper) { this.mapper = mapper; return this; }
        
        /** whether JSON is the ultimate product; 
         * main effect here is to give null for null if true, else to give empty string 
         * <p>
         * conversion to JSON for complex types is done subsequently (often by the framework)
         * <p>
         * default is true */
        public RestValueResolver preferJson(boolean preferJson) { this.preferJson = preferJson; return this; }
        /** whether an outermost string must be wrapped in quotes, because a String return object is treated as
         * already JSON-encoded
         * <p>
         * default is false */
        public RestValueResolver asJerseyOutermostReturnValue(boolean asJerseyReturnJson) {
            isJerseyReturnValue = asJerseyReturnJson;
            return this;
        }

        @Deprecated // since 1.0
        public RestValueResolver raw(Boolean raw) {
            this.raw = raw;
            return this;
        }
        public RestValueResolver useDisplayHints(Boolean useDisplayHints) {
            this.useDisplayHints = useDisplayHints;
            return this;
        }
        private boolean isUseDisplayHints() {
            if (raw!=null) {
                if (raw) {
                    // explicit non-default value takes precedence
                    // (REST API will not allow 'null')
                    return !raw;
                }
                // otherwise pass through
            }

            if (useDisplayHints!=null) return useDisplayHints;
            return true;
        }
        public RestValueResolver skipResolution(Boolean skipResolution) {
            this.skipResolution = skipResolution;
            return this;
        }
        public RestValueResolver suppressIfSecret(String keyName, Boolean suppressIfSecret) {
            if (Boolean.TRUE.equals(suppressIfSecret)) {
                if (Sanitizer.IS_SECRET_PREDICATE.apply(keyName)) {
                    suppressBecauseSecret = true;
                }
            }

            return this;
        }

        public RestValueResolver context(Entity entity) { this.entity = entity; return this; }
        public RestValueResolver timeout(Duration timeout) { this.timeout = timeout; return this; }
        public RestValueResolver immediately(boolean immediately) { this.immediately = immediately; return this; }
        public RestValueResolver renderAs(Object rendererHintSource) { this.rendererHintSource = rendererHintSource; return this; }

        public Object resolve() {
            Object valueResult =
                    Boolean.TRUE.equals(skipResolution)
                            ? valueToResolve
                            : getImmediateValue(valueToResolve, entity, immediately, timeout);
            if (valueResult==UNRESOLVED) valueResult = valueToResolve;
            if (rendererHintSource!=null && isUseDisplayHints()) {
                valueResult = RendererHints.applyDisplayValueHintUnchecked(rendererHintSource, valueResult);
            }
            if (Boolean.TRUE.equals(suppressBecauseSecret)) {
                if (valueResult instanceof BrooklynDslDeferredSupplier) {
                    // deferred supplier not suppressed
                } else {
                    valueResult = Sanitizer.suppress(valueResult);
                }
            }
            return WebResourceUtils.getValueForDisplay(mapper, valueResult, preferJson, isJerseyReturnValue);
        }
        
        private static Object UNRESOLVED = "UNRESOLVED".toCharArray();
        
        private static Object getImmediateValue(Object value, @Nullable Entity context, @Nullable Boolean immediately, @Nullable Duration timeout) {
            return Tasks.resolving(value)
                    .as(Object.class)
                    .defaultValue(UNRESOLVED)
                    .deep()
                    .timeout(timeout)
                    .immediately(immediately == null ? false : immediately.booleanValue())
                    .context(context)
                    .swallowExceptions()
                    .get();
        }

    }

}
