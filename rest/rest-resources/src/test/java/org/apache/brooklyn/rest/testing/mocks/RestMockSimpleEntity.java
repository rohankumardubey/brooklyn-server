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
package org.apache.brooklyn.rest.testing.mocks;

import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.annotation.Effector;
import org.apache.brooklyn.core.annotation.EffectorParam;
import org.apache.brooklyn.core.config.BasicConfigKey;
import org.apache.brooklyn.core.effector.MethodEffector;
import org.apache.brooklyn.core.sensor.BasicAttributeSensor;
import org.apache.brooklyn.entity.software.base.AbstractSoftwareProcessSshDriver;
import org.apache.brooklyn.entity.software.base.SoftwareProcessImpl;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.flags.SetFromFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RestMockSimpleEntity extends SoftwareProcessImpl {

    private static final Logger log = LoggerFactory.getLogger(RestMockSimpleEntity.class);
    
    @Override
    protected void connectSensors() {
        super.connectSensors();
        connectServiceUpIsRunning();
    }

    @SetFromFlag("sampleConfig")
    public static final ConfigKey<String> SAMPLE_CONFIG = new BasicConfigKey<String>(
            String.class, "brooklyn.rest.mock.sample.config", "Mock sample config", "DEFAULT_VALUE");

    public static final ConfigKey<String> SECRET_CONFIG = new BasicConfigKey<String>(
            String.class, "brooklyn.rest.mock.secret.config");

    public static final AttributeSensor<String> SAMPLE_SENSOR = new BasicAttributeSensor<String>(
            String.class, "brooklyn.rest.mock.sample.sensor", "Mock sample sensor");

    public static final MethodEffector<String> SAMPLE_EFFECTOR = new MethodEffector<String>(RestMockSimpleEntity.class, "sampleEffector");
    
    @Effector
    public String sampleEffector(@EffectorParam(name="param1", description="param one") String param1, 
            @EffectorParam(name="param2") Integer param2) {
        log.info("Invoked sampleEffector("+param1+","+param2+")");
        String result = ""+param1+param2;
        sensors().set(SAMPLE_SENSOR, result);
        return result;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Class getDriverInterface() {
        return MockSshDriver.class;
    }
    
    public static class MockSshDriver extends AbstractSoftwareProcessSshDriver {
        @SuppressWarnings("deprecation")
        public MockSshDriver(org.apache.brooklyn.api.entity.EntityLocal entity, SshMachineLocation machine) {
            super(entity, machine);
        }
        @Override
        public boolean isRunning() { return true; }
        @Override
        public void stop() {}
        @Override
        public void kill() {}
        @Override
        public void install() {}
        @Override
        public void customize() {}
        @Override
        public void launch() {}
        @Override
        public void setup() { }
        @Override
        public void copyInstallResources() { }
        @Override
        public void copyRuntimeResources() { }
    }
}
