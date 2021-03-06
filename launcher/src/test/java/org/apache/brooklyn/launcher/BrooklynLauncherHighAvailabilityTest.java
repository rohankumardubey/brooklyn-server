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
package org.apache.brooklyn.launcher;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.ha.HighAvailabilityMode;
import org.apache.brooklyn.api.mgmt.ha.ManagementPlaneSyncRecordPersister;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.core.mgmt.persist.PersistMode;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;

public class BrooklynLauncherHighAvailabilityTest extends AbstractBrooklynLauncherRebindTest {

    private static final Logger log = LoggerFactory.getLogger(BrooklynLauncherHighAvailabilityTest.class);
    
    private BrooklynLauncher primary;
    private BrooklynLauncher secondary;
    private BrooklynLauncher tertiary;

    @Test
    public void testStandbyTakesOverWhenPrimaryTerminatedGracefully() throws Exception {
        doTestStandbyTakesOver(true);
    }

    @Test(invocationCount=10, groups="Integration")
    /** test issues with termination and promotion; 
     * previously we got FileNotFound errors, though these should be fixed with
     * the various PersistenceObjectStore prepare methods */
    public void testStandbyTakesOverWhenPrimaryTerminatedGracefullyManyTimes() throws Exception {
        testStandbyTakesOverWhenPrimaryTerminatedGracefully();
    }

    @Test(groups="Integration") // because slow waiting for timeouts to promote standbys
    public void testStandbyTakesOverWhenPrimaryFails() throws Exception {
        doTestStandbyTakesOver(false);
    }
    
    protected void doTestStandbyTakesOver(boolean stopGracefully) throws Exception {
        log.info("STARTING standby takeover test");
        primary = newLauncherForTests(PersistMode.AUTO, HighAvailabilityMode.AUTO)
                .haHeartbeatTimeout(Duration.millis(1000))
                .start();
        ManagementContext primaryManagementContext = primary.getServerDetails().getManagementContext();
        TestApplication origApp = primaryManagementContext.getEntityManager().createEntity(EntitySpec.create(TestApplication.class));
        log.info("started mgmt primary "+primaryManagementContext);
        
        assertOnlyApp(primaryManagementContext, TestApplication.class);
        primaryManagementContext.getRebindManager().getPersister().waitForWritesCompleted(Asserts.DEFAULT_LONG_TIMEOUT);
        
        // Secondary will come up as standby
        secondary = newLauncherForTests(PersistMode.AUTO, HighAvailabilityMode.STANDBY)
                .haHeartbeatTimeout(Duration.millis(1000))
                .start();
        ManagementContext secondaryManagementContext = secondary.getServerDetails().getManagementContext();
        log.info("started mgmt secondary "+secondaryManagementContext);
        
        // In standby (rather than hot-standby) it will not read the persisted state of apps
        assertNoApps(secondary.getServerDetails().getManagementContext());

        // Terminate primary; expect secondary to take over
        if (stopGracefully) {
            ((ManagementContextInternal)primaryManagementContext).terminate();
        } else {
            ManagementPlaneSyncRecordPersister planePersister = ((ManagementContextInternal)primaryManagementContext).getHighAvailabilityManager().getPersister();
            planePersister.stop(); // can no longer write heartbeats
            ((ManagementContextInternal)primaryManagementContext).terminate();
        }
        
        assertOnlyAppEventually(secondaryManagementContext, TestApplication.class);
        
        // Start tertiary (force up as standby)
        tertiary = newLauncherForTests(PersistMode.AUTO, HighAvailabilityMode.STANDBY)
                .haHeartbeatTimeout(Duration.millis(1000))
                .start();
        ManagementContext tertiaryManagementContext = tertiary.getServerDetails().getManagementContext();
        log.info("started mgmt tertiary "+primaryManagementContext);
        
        assertNoApps(tertiary.getServerDetails().getManagementContext());

        // Terminate secondary; expect tertiary to take over
        if (stopGracefully) {
            ((ManagementContextInternal)secondaryManagementContext).terminate();
        } else {
            ManagementPlaneSyncRecordPersister planePersister = ((ManagementContextInternal)secondaryManagementContext).getHighAvailabilityManager().getPersister();
            planePersister.stop(); // can no longer write heartbeats
            ((ManagementContextInternal)secondaryManagementContext).terminate();
        }
        
        assertOnlyAppEventually(tertiaryManagementContext, TestApplication.class);
    }
    
    @Test
    public void testHighAvailabilityMasterModeFailsIfAlreadyHasMaster() throws Exception {
        primary = newLauncherForTests(PersistMode.AUTO, HighAvailabilityMode.AUTO)
                .start();

        try {
            // Secondary will come up as standby
            secondary = newLauncherForTests(PersistMode.AUTO, HighAvailabilityMode.MASTER)
                    .ignorePersistenceErrors(false)
                    .start();
            Asserts.shouldHaveFailedPreviously();
        } catch (IllegalStateException e) {
            // success
        }
    }

    @Test
    public void testHighAvailabilityStandbyModeFailsIfNoExistingMaster() throws Exception {
        try {
            primary = newLauncherForTests(PersistMode.AUTO, HighAvailabilityMode.STANDBY)
                    .ignorePersistenceErrors(false)
                    .start();
            fail();
        } catch (IllegalStateException e) {
            // success
        }
    }
    
    @Test
    public void testHighAvailabilityHotStandbyModeFailsIfNoExistingMaster() throws Exception {
        try {
            primary = newLauncherForTests(PersistMode.AUTO, HighAvailabilityMode.HOT_STANDBY)
                    .ignorePersistenceErrors(false)
                    .start();
            fail();
        } catch (IllegalStateException e) {
            // success
        }
    }
    
    private void assertOnlyApp(ManagementContext managementContext, Class<? extends Application> expectedType) {
        assertEquals(managementContext.getApplications().size(), 1, "apps="+managementContext.getApplications());
        assertNotNull(Iterables.find(managementContext.getApplications(), Predicates.instanceOf(TestApplication.class), null), "apps="+managementContext.getApplications());
    }
    
    private void assertNoApps(ManagementContext managementContext) {
        if (!managementContext.getApplications().isEmpty())
            log.warn("FAILED assertion (rethrowing), apps="+managementContext.getApplications());
        assertTrue(managementContext.getApplications().isEmpty(), "apps="+managementContext.getApplications());
    }
    
    private void assertOnlyAppEventually(final ManagementContext managementContext, final Class<? extends Application> expectedType) {
        Asserts.succeedsEventually(new Runnable() {
            @Override public void run() {
                assertOnlyApp(managementContext, expectedType);
            }});
    }
}
