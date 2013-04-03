/**********************************************************************************
 * $URL$
 * $Id$
 ***********************************************************************************
 *
 * Copyright (c) 2003, 2004, 2005, 2006, 2008 Sakai Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.opensource.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 **********************************************************************************/

package org.sakaiproject.event.impl;

import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.event.api.LearningResourceStoreProvider;
import org.sakaiproject.event.api.LearningResourceStoreService;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * Core implementation of the LRS integration
 * This will basically just reroute calls over to the set of known {@link LearningResourceStoreProvider}.
 * It also does basic config handling (around enabling/disabling the overall processing) and filtering handled statement origins.
 * 
 * Configuration:
 * 1) Enable LRS processing
 * Default: false
 * lrs.enabled=true
 * 2) Enabled statement origin filters
 * Default: No filters (all statements processed)
 * lrs.origins.filter=tool1,tool2,tool3
 * 
 * @author Aaron Zeckoski (azeckoski @ vt.edu)
 */
public class BaseLearningResourceStoreService implements LearningResourceStoreService, ApplicationContextAware {

    private static final Log log = LogFactory.getLog(BaseLearningResourceStoreService.class);

    /**
     * Stores the complete set of known LRSP providers (from the Spring AC or registered manually)
     */
    private ConcurrentHashMap<String, LearningResourceStoreProvider> providers;
    /**
     * Stores the complete set of origin filters for the LRS service,
     * Anything with an origin that matches the ones in this set will be blocked from being processed
     */
    private HashSet<String> originFilters;

    public void init() {
        providers = new ConcurrentHashMap<String, LearningResourceStoreProvider>();
        // search for known providers
        if (isEnabled() && applicationContext != null) {
            @SuppressWarnings("unchecked")
            Map<String, LearningResourceStoreProvider> beans = applicationContext.getBeansOfType(LearningResourceStoreProvider.class);
            for (LearningResourceStoreProvider lrsp : beans.values()) {
                if (lrsp != null) { // should not be null but this avoids killing everything if it is
                    registerProvider(lrsp);
                }
            }
            log.info("LRS Registered "+beans.size()+" LearningResourceStoreProviders from the Spring AC during service INIT");
        } else {
            log.info("LRS did not search for existing LearningResourceStoreProviders in the system (ac="+applicationContext+", enabled="+isEnabled()+")");
        }
        if (isEnabled() && serverConfigurationService != null) {
            String[] filters = serverConfigurationService.getStrings("lrs.origins.filter");
            if (filters == null || filters.length == 0) {
                log.info("LRS filters are not configured: All statements will be passed through to the LRS");
            } else {
                originFilters = new HashSet<String>(filters.length);
                for (int i = 0; i < filters.length; i++) {
                    if (filters[i] != null) {
                        originFilters.add(filters[i]);
                    }
                }
                log.info("LRS found "+originFilters.size()+" origin filters: "+originFilters);
            }
        }
        log.info("LRS INIT: enabled="+isEnabled());
    }

    public void destroy() {
        if (providers != null) {
            providers.clear();
        }
        originFilters = null;
        providers = null;
        log.info("LRS DESTROY");
    }

    /* (non-Javadoc)
     * @see org.sakaiproject.event.api.LearningResourceStoreService#registerStatement(org.sakaiproject.event.api.LearningResourceStoreService.LRS_Statement, java.lang.String)
     */
    public void registerStatement(LRS_Statement statement, String origin) {
        if (isEnabled() 
                && providers != null && !providers.isEmpty()) {
            // filter out certain tools and statement origins
            boolean skip = false;
            if (originFilters != null && !originFilters.isEmpty()) {
                origin = StringUtils.trimToNull(origin);
                if (origin != null && originFilters.contains(origin)) {
                    if (log.isDebugEnabled()) log.debug("LRS statement skipped because origin ("+origin+") matches the originFilter");
                    skip = true;
                }
            }
            if (!skip) {
                // process this statement
                if (log.isDebugEnabled()) log.debug("LRS statement being processed, origin="+origin+", statement="+statement);
                for (LearningResourceStoreProvider lrsp : providers.values()) {
                    // run the statement processing in a new thread
                    String threadName = "LRS_"+lrsp.getID();
                    Thread t = new Thread(new RunStatementThread(lrsp, statement), threadName); // each provider has it's own thread
                    t.setDaemon(true); // allow this thread to be killed when the JVM dies
                    t.start();
                }
            }
        }
    }

    /**
     * internal class to support threaded execution of statements processing
     */
    private static class RunStatementThread implements Runnable {
        final LearningResourceStoreProvider lrsp;
        final LRS_Statement statement;
        public RunStatementThread(LearningResourceStoreProvider lrsp, LRS_Statement statement) {
            this.lrsp = lrsp;
            this.statement = statement;
        }
        @Override
        public void run() {
            try {
                lrsp.handleStatement(statement);
            } catch (Exception e) {
                log.error("LRS Failure running LRS statement in provider ("+lrsp.getID()+"): statement=("+statement+"): "+e, e);
            }
        }
    };

    /* (non-Javadoc)
     * @see org.sakaiproject.event.api.LearningResourceStoreService#isEnabled()
     */
    public boolean isEnabled() {
        boolean enabled = false;
        if (serverConfigurationService != null) {
            enabled = serverConfigurationService.getBoolean("lrs.enabled", enabled);
        }
        return enabled;
    }

    /* (non-Javadoc)
     * @see org.sakaiproject.event.api.LearningResourceStoreService#registerProvider(org.sakaiproject.event.api.LearningResourceStoreProvider)
     */
    public boolean registerProvider(LearningResourceStoreProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("LRS provider must not be null");
        }
        return providers.put(provider.getID(), provider) != null;
    }


    ServerConfigurationService serverConfigurationService;
    public void setServerConfigurationService(ServerConfigurationService serverConfigurationService) {
        this.serverConfigurationService = serverConfigurationService;
    }

    ApplicationContext applicationContext;
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

}
