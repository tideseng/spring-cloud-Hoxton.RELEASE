/*
 *
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.loadbalancer;

import com.google.common.annotations.VisibleForTesting;
import com.netflix.client.ClientFactory;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.DefaultClientConfigImpl;
import com.netflix.client.config.IClientConfig;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A LoadBalancer that has the capabilities to obtain the candidate list of
 * servers using a dynamic source. i.e. The list of servers can potentially be
 * changed at Runtime. It also contains facilities wherein the list of servers
 * can be passed through a Filter criteria to filter out servers that do not
 * meet the desired criteria.
 * 
 * @author stonse
 * 
 */
public class DynamicServerListLoadBalancer<T extends Server> extends BaseLoadBalancer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicServerListLoadBalancer.class);

    boolean isSecure = false;
    boolean useTunnel = false;

    // to keep track of modification of server lists
    protected AtomicBoolean serverListUpdateInProgress = new AtomicBoolean(false);

    volatile ServerList<T> serverListImpl;

    volatile ServerListFilter<T> filter;

    protected final ServerListUpdater.UpdateAction updateAction = new ServerListUpdater.UpdateAction() {
        @Override
        public void doUpdate() {
            updateListOfServers();
        }
    };

    protected volatile ServerListUpdater serverListUpdater;

    public DynamicServerListLoadBalancer() {
        super();
    }

    @Deprecated
    public DynamicServerListLoadBalancer(IClientConfig clientConfig, IRule rule, IPing ping, 
            ServerList<T> serverList, ServerListFilter<T> filter) {
        this(
                clientConfig,
                rule,
                ping,
                serverList,
                filter,
                new PollingServerListUpdater()
        );
    }

    public DynamicServerListLoadBalancer(IClientConfig clientConfig, IRule rule, IPing ping,
                                         ServerList<T> serverList, ServerListFilter<T> filter,
                                         ServerListUpdater serverListUpdater) {
        super(clientConfig, rule, ping); // 调用父类构造函数
        this.serverListImpl = serverList;
        this.filter = filter;
        this.serverListUpdater = serverListUpdater;
        if (filter instanceof AbstractServerListFilter) {
            ((AbstractServerListFilter) filter).setLoadBalancerStats(getLoadBalancerStats());
        }
        restOfInit(clientConfig); // 在这里会更新服务列表
    }

    public DynamicServerListLoadBalancer(IClientConfig clientConfig) {
        initWithNiwsConfig(clientConfig);
    }
    
    @Override
    public void initWithNiwsConfig(IClientConfig clientConfig) {
        try {
            super.initWithNiwsConfig(clientConfig);
            String niwsServerListClassName = clientConfig.getPropertyAsString(
                    CommonClientConfigKey.NIWSServerListClassName,
                    DefaultClientConfigImpl.DEFAULT_SEVER_LIST_CLASS);

            ServerList<T> niwsServerListImpl = (ServerList<T>) ClientFactory
                    .instantiateInstanceWithClientConfig(niwsServerListClassName, clientConfig);
            this.serverListImpl = niwsServerListImpl;

            if (niwsServerListImpl instanceof AbstractServerList) {
                AbstractServerListFilter<T> niwsFilter = ((AbstractServerList) niwsServerListImpl)
                        .getFilterImpl(clientConfig);
                niwsFilter.setLoadBalancerStats(getLoadBalancerStats());
                this.filter = niwsFilter;
            }

            String serverListUpdaterClassName = clientConfig.getPropertyAsString(
                    CommonClientConfigKey.ServerListUpdaterClassName,
                    DefaultClientConfigImpl.DEFAULT_SERVER_LIST_UPDATER_CLASS
            );

            this.serverListUpdater = (ServerListUpdater) ClientFactory
                    .instantiateInstanceWithClientConfig(serverListUpdaterClassName, clientConfig);

            restOfInit(clientConfig);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Exception while initializing NIWSDiscoveryLoadBalancer:"
                            + clientConfig.getClientName()
                            + ", niwsClientConfig:" + clientConfig, e);
        }
    }

    void restOfInit(IClientConfig clientConfig) {
        boolean primeConnection = this.isEnablePrimingConnections();
        // turn this off to avoid duplicated asynchronous priming done in BaseLoadBalancer.setServerList()
        this.setEnablePrimingConnections(false);
        enableAndInitLearnNewServersFeature(); // 通过定时任务默认每隔30秒动态更新服务列表（调用updateListOfServers方法）

        updateListOfServers(); // 更新服务列表
        if (primeConnection && this.getPrimeConnections() != null) {
            this.getPrimeConnections()
                    .primeConnections(getReachableServers());
        }
        this.setEnablePrimingConnections(primeConnection);
        LOGGER.info("DynamicServerListLoadBalancer for client {} initialized: {}", clientConfig.getClientName(), this.toString());
    }
    
    
    @Override
    public void setServersList(List lsrv) {
        super.setServersList(lsrv);  // 设置服务列表
        List<T> serverList = (List<T>) lsrv;
        Map<String, List<Server>> serversInZones = new HashMap<String, List<Server>>();
        for (Server server : serverList) {
            // make sure ServerStats is created to avoid creating them on hot
            // path
            getLoadBalancerStats().getSingleServerStat(server);
            String zone = server.getZone();
            if (zone != null) {
                zone = zone.toLowerCase();
                List<Server> servers = serversInZones.get(zone);
                if (servers == null) {
                    servers = new ArrayList<Server>();
                    serversInZones.put(zone, servers);
                }
                servers.add(server);
            }
        }
        setServerListForZones(serversInZones);
    }

    protected void setServerListForZones(
            Map<String, List<Server>> zoneServersMap) {
        LOGGER.debug("Setting server list for zones: {}", zoneServersMap);
        getLoadBalancerStats().updateZoneServerMapping(zoneServersMap);
    }

    public ServerList<T> getServerListImpl() {
        return serverListImpl;
    }

    public void setServerListImpl(ServerList<T> niwsServerList) {
        this.serverListImpl = niwsServerList;
    }

    public ServerListFilter<T> getFilter() {
        return filter;
    }

    public void setFilter(ServerListFilter<T> filter) {
        this.filter = filter;
    }

    public ServerListUpdater getServerListUpdater() {
        return serverListUpdater;
    }

    public void setServerListUpdater(ServerListUpdater serverListUpdater) {
        this.serverListUpdater = serverListUpdater;
    }

    @Override
    /**
     * Makes no sense to ping an inmemory disc client
     * 
     */
    public void forceQuickPing() {
        // no-op
    }

    /**
     * Feature that lets us add new instances (from AMIs) to the list of
     * existing servers that the LB will use Call this method if you want this
     * feature enabled
     */
    public void enableAndInitLearnNewServersFeature() {
        LOGGER.info("Using serverListUpdater {}", serverListUpdater.getClass().getSimpleName());
        serverListUpdater.start(updateAction);
    }

    private String getIdentifier() {
        return this.getClientConfig().getClientName();
    }

    public void stopServerListRefreshing() {
        if (serverListUpdater != null) {
            serverListUpdater.stop();
        }
    }

    @VisibleForTesting
    public void updateListOfServers() { // Ribbon在工作时首选会通过ServerList来获取所有可用的服务列表，然后通过ServerListFilter过虑掉一部分地址，最后在剩下的地址中通过IRule选择出一个服务作为最终结果
        List<T> servers = new ArrayList<T>();
        if (serverListImpl != null) {
            servers = serverListImpl.getUpdatedListOfServers(); // 调用实现类方法获取ServerList可用服务列表（这里是调用Eureka/Nacos获取服务列表的入口）
            LOGGER.debug("List of Servers for {} obtained from Discovery client: {}",
                    getIdentifier(), servers);

            if (filter != null) {
                servers = filter.getFilteredListOfServers(servers); //过滤服务
                LOGGER.debug("Filtered List of Servers for {} obtained from Discovery client: {}",
                        getIdentifier(), servers);
            }
        }
        updateAllServerList(servers); // 更新服务列表
    }

    /**
     * Update the AllServer list in the LoadBalancer if necessary and enabled
     * 
     * @param ls
     */
    protected void updateAllServerList(List<T> ls) {
        // other threads might be doing this - in which case, we pass
        if (serverListUpdateInProgress.compareAndSet(false, true)) { // CAS操作，轻量级锁
            try {
                for (T s : ls) {
                    s.setAlive(true); // set so that clients can start using these
                                      // servers right away instead
                                      // of having to wait out the ping cycle.
                }
                setServersList(ls); // 设置服务列表
                super.forceQuickPing();
            } finally {
                serverListUpdateInProgress.set(false); // 操作完毕后设置为false
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DynamicServerListLoadBalancer:");
        sb.append(super.toString());
        sb.append("ServerList:" + String.valueOf(serverListImpl));
        return sb.toString();
    }
    
    @Override 
    public void shutdown() {
        super.shutdown();
        stopServerListRefreshing();
    }


    @Monitor(name="LastUpdated", type=DataSourceType.INFORMATIONAL)
    public String getLastUpdate() {
        return serverListUpdater.getLastUpdate();
    }

    @Monitor(name="DurationSinceLastUpdateMs", type= DataSourceType.GAUGE)
    public long getDurationSinceLastUpdateMs() {
        return serverListUpdater.getDurationSinceLastUpdateMs();
    }

    @Monitor(name="NumUpdateCyclesMissed", type=DataSourceType.GAUGE)
    public int getNumberMissedCycles() {
        return serverListUpdater.getNumberMissedCycles();
    }

    @Monitor(name="NumThreads", type=DataSourceType.GAUGE)
    public int getCoreThreads() {
        return serverListUpdater.getCoreThreads();
    }
}
