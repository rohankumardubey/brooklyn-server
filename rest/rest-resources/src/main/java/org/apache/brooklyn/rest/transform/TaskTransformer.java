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
package org.apache.brooklyn.rest.transform;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.brooklyn.util.text.StringEscapes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.mgmt.HasTaskChildren;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags;
import org.apache.brooklyn.core.mgmt.BrooklynTaskTags.WrappedStream;
import org.apache.brooklyn.rest.domain.LinkWithMetadata;
import org.apache.brooklyn.rest.domain.TaskSummary;
import org.apache.brooklyn.rest.resources.EntityResource.InterestingTasksFirstComparator;
import org.apache.brooklyn.rest.util.WebResourceUtils;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.task.TaskInternal;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.text.Strings;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;

import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.apache.brooklyn.rest.api.ActivityApi;
import org.apache.brooklyn.rest.api.EntityApi;
import static org.apache.brooklyn.rest.util.WebResourceUtils.serviceUriBuilder;

public class TaskTransformer {

    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(TaskTransformer.class);

    public static final Function<Task<?>, TaskSummary> fromTask(final UriBuilder ub) {
        return new Function<Task<?>, TaskSummary>() {
            @Override
            public TaskSummary apply(@Nullable Task<?> input) {
                return taskSummary(input, ub);
            }
        };
    };

    public static TaskSummary taskSummary(Task<?> task, UriBuilder ub) {
      try {
        Preconditions.checkNotNull(task);
        Entity entity = BrooklynTaskTags.getContextEntity(task);
        String entityId;
        String entityDisplayName;
        URI entityLink;
        
        String selfLink = asLink(task, ub).getLink();

        if (entity != null) {
            entityId = entity.getId();
            entityDisplayName = entity.getDisplayName();
            String appId = entity.getApplicationId();
            entityLink = (appId != null) ? serviceUriBuilder(ub, EntityApi.class, "get").build(appId, entityId) : null;
        } else {
            entityId = null;
            entityDisplayName = null;
            entityLink = null;
        }

        List<LinkWithMetadata> children = Collections.emptyList();
        if (task instanceof HasTaskChildren) {
            children = new ArrayList<LinkWithMetadata>();
            for (Task<?> t: ((HasTaskChildren)task).getChildren()) {
                children.add(asLink(t, ub));
            }
        }
        
        Map<String,LinkWithMetadata> streams = new MutableMap<String, LinkWithMetadata>();
        for (WrappedStream stream: BrooklynTaskTags.streams(task)) {
            MutableMap<String, Object> metadata = MutableMap.<String,Object>of("name", stream.streamType);
            if (stream.streamSize.get()!=null) {
                metadata.add("size", stream.streamSize.get());
                metadata.add("sizeText", Strings.makeSizeString(stream.streamSize.get()));
            }
            String link = selfLink + "/stream/" + StringEscapes.escapeHtmlFormUrl(stream.streamType).replaceAll("\\+", "%20");
            streams.put(stream.streamType, new LinkWithMetadata(link, metadata));
        }
        
        Map<String,URI> links = MutableMap.of("self", new URI(selfLink),
                "children", new URI(selfLink+"/"+"children"));
        if (entityLink!=null) links.put("entity", entityLink);
        
        Object result;
        try {
            if (task.isDone()) {
                result = WebResourceUtils.getValueForDisplay(task.get(), true, false);
            } else {
                result = null;
            }
        } catch (Throwable t) {
            result = Exceptions.collapseTextInContext(t, task);
        }
        
        return new TaskSummary(task.getId(), task.getDisplayName(), task.getDescription(), entityId, entityDisplayName, 
                task.getTags(), ifPositive(task.getSubmitTimeUtc()), ifPositive(task.getStartTimeUtc()), ifPositive(task.getEndTimeUtc()),
                task.getStatusSummary(), result, task.isError(), task.isCancelled(),
                children, asLink(task.getSubmittedByTask(), ub),
                task.isDone() ? null : task instanceof TaskInternal ? asLink(((TaskInternal<?>)task).getBlockingTask(), ub) : null,
                task.isDone() ? null : task instanceof TaskInternal ? ((TaskInternal<?>)task).getBlockingDetails() : null, 
                task.getStatusDetail(true),
                streams,
                links);
      } catch (URISyntaxException e) {
          // shouldn't happen
          throw Exceptions.propagate(e);
      }
    }

    private static Long ifPositive(Long time) {
        if (time==null || time<=0) return null;
        return time;
    }

    public static LinkWithMetadata asLink(Task<?> t, UriBuilder ub) {
        if (t==null) return null;
        MutableMap<String,Object> data = new MutableMap<String,Object>();
        data.put("id", t.getId());
        if (t.getDisplayName()!=null) data.put("taskName", t.getDisplayName());
        Entity entity = BrooklynTaskTags.getContextEntity(t);
        if (entity!=null) {
            data.put("entityId", entity.getId());
            if (entity.getDisplayName()!=null) data.put("entityDisplayName", entity.getDisplayName());
        }
        URI taskUri = serviceUriBuilder(ub, ActivityApi.class, "get").build(t.getId());
        return new LinkWithMetadata(taskUri.toString(), data);
    }
    
    public static List<TaskSummary> fromTasks(List<Task<?>> tasksToScan, int limit, Boolean recurse, Entity entity, UriInfo ui) {
        int sizeRemaining = limit;
        if (limit>0) {
            tasksToScan = MutableList.copyOf(Ordering.from(new InterestingTasksFirstComparator(entity)).leastOf(tasksToScan, limit));
        }
        Map<String,Task<?>> tasksLoaded = MutableMap.of();
        
        while (!tasksToScan.isEmpty()) {
            Task<?> t = tasksToScan.remove(0);
            if (tasksLoaded.put(t.getId(), t)==null) {
                if (--sizeRemaining==0) {
                    break;
                }
                if (Boolean.TRUE.equals(recurse)) {
                    if (t instanceof HasTaskChildren) {
                        Iterables.addAll(tasksToScan, ((HasTaskChildren) t).getChildren() );
                    }
                }
            }
        }
        return new LinkedList<TaskSummary>(Collections2.transform(tasksLoaded.values(), 
            TaskTransformer.fromTask(ui.getBaseUriBuilder())));
    }
}
