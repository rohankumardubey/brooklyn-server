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
package org.apache.brooklyn.rest.util;

import java.io.IOException;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.BrooklynDslDeferredSupplier;
import org.apache.brooklyn.core.catalog.internal.CatalogUtils;
import org.apache.brooklyn.core.typereg.RegisteredTypeNaming;
import org.apache.brooklyn.rest.domain.ApiError;
import org.apache.brooklyn.rest.util.json.BrooklynJacksonJsonProvider;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.exceptions.PropagatedRuntimeException;
import org.apache.brooklyn.util.net.Urls;
import org.apache.brooklyn.util.text.StringEscapes.JavaStringEscapes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

public class WebResourceUtils {

    private static final Logger log = LoggerFactory.getLogger(WebResourceUtils.class);

    /** @throws WebApplicationException with an ApiError as its body and the given status as its response code. */
    public static WebApplicationException throwWebApplicationException(Response.Status status, String format, Object... args) {
        return throwWebApplicationException(status, null, format, args);
    }
    
    /** @throws WebApplicationException with an ApiError as its body and the given status as its response code. */
    public static WebApplicationException throwWebApplicationException(Response.Status status, Throwable exception) {
        return throwWebApplicationException(status, exception, null);
    }
    
    /** @throws WebApplicationException with an ApiError as its body and the given status as its response code.
     * Exception and/or format can be null, and will be filled in / prefixed as appropriate. */
    public static WebApplicationException throwWebApplicationException(Response.Status status, Throwable exception, String format, Object... args) {
        String suppliedMsg = format==null ? null : String.format(format, args);
        String fullMsg = suppliedMsg;
        if (exception!=null) {
            if (fullMsg==null) fullMsg = Exceptions.collapseText(exception);
            else fullMsg = suppliedMsg + ": "+Exceptions.collapseText(exception);
        }
        if (log.isDebugEnabled()) {
            log.debug("responding {} {} ({})",
                    new Object[]{status.getStatusCode(), status.getReasonPhrase(), fullMsg});
        }
        ApiError apiError = 
            (exception != null ? ApiError.builderFromThrowable(exception).prefixMessage(suppliedMsg) 
                : ApiError.builder().message(fullMsg==null ? "" : fullMsg))
            .errorCode(status).build();
        // including a Throwable is the only way to include a message with the WebApplicationException - ugly!
        throw new WebApplicationException(
            exception==null ? new Throwable(apiError.toString()) :
                suppliedMsg==null ? exception :
                new PropagatedRuntimeException(suppliedMsg, exception), 
            apiError.asJsonResponse());
    }

    /** @throws WebApplicationException With code 500 internal server error */
    public static WebApplicationException serverError(String format, Object... args) {
        return throwWebApplicationException(Response.Status.INTERNAL_SERVER_ERROR, format, args);
    }

    /** @throws WebApplicationException With code 400 bad request */
    public static WebApplicationException badRequest(String format, Object... args) {
        return throwWebApplicationException(Response.Status.BAD_REQUEST, format, args);
    }

    /** @throws WebApplicationException With code 400 bad request */
    public static WebApplicationException badRequest(Throwable t) {
        return throwWebApplicationException(Response.Status.BAD_REQUEST, t);
    }

    /** @throws WebApplicationException With code 400 bad request */
    public static WebApplicationException badRequest(Throwable t, String prefix, Object... prefixArgs) {
        return throwWebApplicationException(Response.Status.BAD_REQUEST, t, prefix, prefixArgs);
    }

    /** @throws WebApplicationException With code 401 unauthorized */
    public static WebApplicationException unauthorized(String format, Object... args) {
        return throwWebApplicationException(Response.Status.UNAUTHORIZED, format, args);
    }

    /** @throws WebApplicationException With code 403 forbidden */
    public static WebApplicationException forbidden(String format, Object... args) {
        return throwWebApplicationException(Response.Status.FORBIDDEN, format, args);
    }

    /** @throws WebApplicationException With code 404 not found */
    public static WebApplicationException notFound(String format, Object... args) {
        return throwWebApplicationException(Response.Status.NOT_FOUND, format, args);
    }

    /** @throws WebApplicationException With code 412 precondition failed */
    public static WebApplicationException preconditionFailed(String format, Object... args) {
        return throwWebApplicationException(Response.Status.PRECONDITION_FAILED, format, args);
    }

    public final static Map<String,com.google.common.net.MediaType> IMAGE_FORMAT_MIME_TYPES = ImmutableMap.<String, com.google.common.net.MediaType>builder()
            .put("jpg", com.google.common.net.MediaType.JPEG)
            .put("jpeg", com.google.common.net.MediaType.JPEG)
            .put("png", com.google.common.net.MediaType.PNG)
            .put("gif", com.google.common.net.MediaType.GIF)
            .put("svg", com.google.common.net.MediaType.SVG_UTF_8)
            .build();
    
    public static MediaType getImageMediaTypeFromExtension(String extension) {
        com.google.common.net.MediaType mime = IMAGE_FORMAT_MIME_TYPES.get(extension.toLowerCase());
        if (mime==null) return null;
        try {
            return MediaType.valueOf(mime.toString());
        } catch (Exception e) {
            log.warn("Unparseable MIME type "+mime+"; ignoring ("+e+")");
            Exceptions.propagateIfFatal(e);
            return null;
        }
    }

    /** as {@link #getValueForDisplay(ObjectMapper, Object, boolean, boolean)} with no mapper
     * (so will only handle a subset of types) */
    public static Object getValueForDisplay(Object value, boolean preferJson, boolean isJerseyReturnValue) {
        return getValueForDisplay(null, value, preferJson, isJerseyReturnValue);
    }
    
    /** returns an object which jersey will handle nicely, converting to json,
     * sometimes wrapping in quotes if needed (for outermost json return types);
     * if json is not preferred, this simply applies a toString-style rendering */ 
    public static Object getValueForDisplay(ObjectMapper mapper, Object value, boolean preferJson, boolean isJerseyReturnValue) {
        if (preferJson) {
            if (value==null) return null;
            Object result = value;
            // no serialization checks required, with new smart-mapper which does toString
            // (note there is more sophisticated logic in git history however)
            result = value;
            if (result instanceof BrooklynDslDeferredSupplier) {
                result = result.toString();
            }
            if (isJerseyReturnValue) {
                if (result instanceof String) {
                    // Jersey does not do json encoding if the return type is a string,
                    // expecting the returner to do the json encoding himself
                    // cf discussion at https://github.com/dropwizard/dropwizard/issues/231
                    result = JavaStringEscapes.wrapJavaString((String)result);
                }
            }
            
            return result;
        } else {
            if (value==null) return "";
            return value.toString();            
        }
    }

    public static String getPathFromVersionedId(String versionedId) {
        if (RegisteredTypeNaming.isUsableTypeColonVersion(versionedId)) {
            String symbolicName = CatalogUtils.getSymbolicNameFromVersionedId(versionedId);
            String version = CatalogUtils.getVersionFromVersionedId(versionedId);
            return Urls.encode(symbolicName) + "/" + Urls.encode(version);
        } else {
            return Urls.encode(versionedId);
        }
    }

    /** Sets the {@link HttpServletResponse} target (last argument) from the given source {@link Response};
     * useful in filters where we might have a {@link Response} and need to set up an {@link HttpServletResponse}.
     */
    public static void applyJsonResponse(ManagementContext mgmt, Response source, HttpServletResponse target) throws IOException {
        target.setStatus(source.getStatus());
        target.setContentType(MediaType.APPLICATION_JSON);
        target.setCharacterEncoding("UTF-8");
        target.getWriter().write(BrooklynJacksonJsonProvider.findAnyObjectMapper(mgmt).writeValueAsString(source.getEntity()));
    }

    /**
     * Provides a builder with the REST URI of a resource.
     * @param baseUriBuilder An {@link UriBuilder} pointing at the base of the REST API.
     * @param resourceClass The target resource class.
     * @return A new {@link UriBuilder} that targets the specified REST resource.
     */
    public static UriBuilder resourceUriBuilder(UriBuilder baseUriBuilder, Class<?> resourceClass) {
        return UriBuilder.fromPath(baseUriBuilder.build().getPath())
                .path(resourceClass);
    }

    /**
     * Provides a builder with the REST URI of a service provided by a resource.
     * @param baseUriBuilder An {@link UriBuilder} pointing at the base of the REST API.
     * @param resourceClass The target resource class.
     * @param method The target service (e.g. class method).
     * @return A new {@link UriBuilder} that targets the specified service of the REST resource.
     */
    public static UriBuilder serviceUriBuilder(UriBuilder baseUriBuilder, Class<?> resourceClass, String method) {
        return resourceUriBuilder(baseUriBuilder, resourceClass).path(resourceClass, method);
    }

    /**
     * Provides a builder with the absolute REST URI of a service provided by a resource.
     * @param baseUriBuilder An {@link UriBuilder} pointing at the base of the REST API.
     * @param resourceClass The target resource class.
     * @param method The target service (e.g. class method).
     * @return A new {@link UriBuilder} that targets the specified service of the REST resource.
     */
    public static UriBuilder serviceAbsoluteUriBuilder(UriBuilder baseUriBuilder, Class<?> resourceClass, String method) {
        return  baseUriBuilder
                    .path(resourceClass)
                    .path(resourceClass, method);
    }

}
