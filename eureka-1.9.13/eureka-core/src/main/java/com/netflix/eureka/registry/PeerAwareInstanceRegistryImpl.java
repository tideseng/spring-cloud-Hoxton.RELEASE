/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.registry;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.netflix.appinfo.AmazonInfo;
import com.netflix.appinfo.AmazonInfo.MetaDataKey;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.DataCenterInfo.Name;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.eureka.registry.rule.DownOrStartingRule;
import com.netflix.eureka.registry.rule.FirstMatchWinsCompositeRule;
import com.netflix.eureka.registry.rule.InstanceStatusOverrideRule;
import com.netflix.eureka.registry.rule.LeaseExistsRule;
import com.netflix.eureka.registry.rule.OverrideExistsRule;
import com.netflix.eureka.resources.CurrentRequestVersion;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.Version;
import com.netflix.eureka.cluster.PeerEurekaNode;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.lease.Lease;
import com.netflix.eureka.resources.ASGResource.ASGStatus;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.MeasuredRate;
import com.netflix.servo.DefaultMonitorRegistry;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles replication of all operations to {@link AbstractInstanceRegistry} to peer
 * <em>Eureka</em> nodes to keep them all in sync.
 *
 * <p>
 * Primary operations that are replicated are the
 * <em>Registers,Renewals,Cancels,Expirations and Status Changes</em>
 * </p>
 *
 * <p>
 * When the eureka server starts up it tries to fetch all the registry
 * information from the peer eureka nodes.If for some reason this operation
 * fails, the server does not allow the user to get the registry information for
 * a period specified in
 * {@link com.netflix.eureka.EurekaServerConfig#getWaitTimeInMsWhenSyncEmpty()}.
 * </p>
 *
 * <p>
 * One important thing to note about <em>renewals</em>.If the renewal drops more
 * than the specified threshold as specified in
 * {@link com.netflix.eureka.EurekaServerConfig#getRenewalPercentThreshold()} within a period of
 * {@link com.netflix.eureka.EurekaServerConfig#getRenewalThresholdUpdateIntervalMs()}, eureka
 * perceives this as a danger and stops expiring instances.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim
 *
 */
@Singleton
public class PeerAwareInstanceRegistryImpl extends AbstractInstanceRegistry implements PeerAwareInstanceRegistry {
    private static final Logger logger = LoggerFactory.getLogger(PeerAwareInstanceRegistryImpl.class);

    private static final String US_EAST_1 = "us-east-1";
    private static final int PRIME_PEER_NODES_RETRY_MS = 30000;

    private long startupTime = 0;
    private boolean peerInstancesTransferEmptyOnStartup = true;

    public enum Action { // Eureka Server同步操作类型（Eureka Server接收到Eureka Client的Register注册、Heartbeat心跳续约、Cancel下线、StatusUpdate添加覆盖状态、DeleteStatusOverride删除覆盖状态操作后，默认固定间隔500毫秒向集群其它Eureka Server节点同步）
        Heartbeat, Register, Cancel, StatusUpdate, DeleteStatusOverride;

        private com.netflix.servo.monitor.Timer timer = Monitors.newTimer(this.name());

        public com.netflix.servo.monitor.Timer getTimer() {
            return this.timer;
        }
    }

    private static final Comparator<Application> APP_COMPARATOR = new Comparator<Application>() {
        public int compare(Application l, Application r) {
            return l.getName().compareTo(r.getName());
        }
    };

    private final MeasuredRate numberOfReplicationsLastMin;

    protected final EurekaClient eurekaClient;
    protected volatile PeerEurekaNodes peerEurekaNodes;

    private final InstanceStatusOverrideRule instanceStatusOverrideRule;

    private Timer timer = new Timer(
            "ReplicaAwareInstanceRegistry - RenewalThresholdUpdater", true);

    @Inject
    public PeerAwareInstanceRegistryImpl( // 由子类调用其构造函数
            EurekaServerConfig serverConfig,
            EurekaClientConfig clientConfig,
            ServerCodecs serverCodecs,
            EurekaClient eurekaClient
    ) {
        super(serverConfig, clientConfig, serverCodecs); // 调用父类AbstractInstanceRegistry构造函数
        this.eurekaClient = eurekaClient;
        this.numberOfReplicationsLastMin = new MeasuredRate(1000 * 60 * 1);
        // We first check if the instance is STARTING or DOWN, then we check explicit overrides,
        // then we check the status of a potentially existing lease.
        this.instanceStatusOverrideRule = new FirstMatchWinsCompositeRule(new DownOrStartingRule(),
                new OverrideExistsRule(overriddenInstanceStatusMap), new LeaseExistsRule());
    }

    @Override
    protected InstanceStatusOverrideRule getInstanceInfoOverrideRule() {
        return this.instanceStatusOverrideRule;
    }

    @Override
    public void init(PeerEurekaNodes peerEurekaNodes) throws Exception { // DefaultEurekaServerContext的初始化方法中调用
        this.numberOfReplicationsLastMin.start();
        this.peerEurekaNodes = peerEurekaNodes;
        initializedResponseCache(); // 初始化ResponseCacheImpl缓存
        scheduleRenewalThresholdUpdateTask(); // 启动定时器，默认每隔15分钟更新每分钟最小续约数量阈值，用来判断是否需要开启自我保护机制
        initRemoteRegionRegistry();

        try {
            Monitors.registerObject(this);
        } catch (Throwable e) {
            logger.warn("Cannot register the JMX monitor for the InstanceRegistry :", e);
        }
    }

    /**
     * Perform all cleanup and shutdown operations.
     */
    @Override
    public void shutdown() {
        try {
            DefaultMonitorRegistry.getInstance().unregister(Monitors.newObjectMonitor(this));
        } catch (Throwable t) {
            logger.error("Cannot shutdown monitor registry", t);
        }
        try {
            peerEurekaNodes.shutdown();
        } catch (Throwable t) {
            logger.error("Cannot shutdown ReplicaAwareInstanceRegistry", t);
        }
        numberOfReplicationsLastMin.stop();

        super.shutdown();
    }

    /**
     * Schedule the task that updates <em>renewal threshold</em> periodically.
     * The renewal threshold would be used to determine if the renewals drop
     * dramatically because of network partition and to protect expiring too
     * many instances at a time.
     *
     */
    private void scheduleRenewalThresholdUpdateTask() { // 启动定时器，默认每隔15分钟更新每分钟最小续约数量阈值，用来判断是否需要开启自我保护机制
        timer.schedule(new TimerTask() {
                           @Override
                           public void run() {
                               updateRenewalThreshold(); // 定时更新客户端数量的逻辑，默认每隔15分钟更新每分钟最小续约数量阈值，用来判断是否需要开启自我保护机制
                           }
                       }, serverConfig.getRenewalThresholdUpdateIntervalMs(), // 延迟15分钟执行
                serverConfig.getRenewalThresholdUpdateIntervalMs()); // 每15分钟执行一次
    }

    /**
     * Populates the registry information from a peer eureka node. This
     * operation fails over to other nodes until the list is exhausted if the
     * communication fails.
     */
    @Override
    public int syncUp() { // 服务同步，从相邻的Eureka Server节点复制实例信息（最大重试5次），然后注册到当前Server节点
        // Copy entire entry from neighboring DS node
        int count = 0; // 所有应用的实例数量总和
        // 实际上只有当shouldRegisterWithEureka=true && shouldFetchRegistry=true时服务同步才会生效
        for (int i = 0; ((i < serverConfig.getRegistrySyncRetries()) && (count == 0)); i++) { // 未获取到注册信息时，默认最大重试5次（只有当eureka.client.register-with-eureka=true的时候才会是5，反之则为0）
            if (i > 0) { // 未获取到注册信息时，默认等待30秒
                try {
                    Thread.sleep(serverConfig.getRegistrySyncRetryWaitMs()); // 默认等待30秒
                } catch (InterruptedException e) {
                    logger.warn("Interrupted during registry transfer..");
                    break;
                }
            }
            Applications apps = eurekaClient.getApplications(); // 从Client客户端localRegionApps本地缓存中获取注册信息（Eureka Server端也是一个Eureka Client端，会维护所有应用信息在本地Client缓存，只有当eureka.client.fetch-registry=true时才会从Server节点获取注册信息）
            for (Application app : apps.getRegisteredApplications()) { // 获取Application列表，并进行遍历
                for (InstanceInfo instance : app.getInstances()) { // 遍历应用的每个实例
                    try {
                        if (isRegisterable(instance)) { // 判断是否能够注册，默认都能注册
                            register(instance, instance.getLeaseInfo().getDurationInSecs(), true); // 节点启动时通过服务同步注册服务实例信息
                            count++;
                        }
                    } catch (Throwable t) {
                        logger.error("During DS init copy", t);
                    }
                }
            }
        }
        return count;
    }

    @Override
    public void openForTraffic(ApplicationInfoManager applicationInfoManager, int count) { // 标识自身server的状态为UP，表示可以开始接收请求，并开启每隔60秒的服务剔除定时任务
        // Renewals happen every 30 seconds and for a minute it should be a factor of 2.
        this.expectedNumberOfClientsSendingRenews = count; // 预期每分钟收到的续约数量=实例总数
        updateRenewsPerMinThreshold(); // 初始化Eureka上下文时更新自我保护阈值
        logger.info("Got {} instances from neighboring DS node", count);
        logger.info("Renew threshold is: {}", numberOfRenewsPerMinThreshold);
        this.startupTime = System.currentTimeMillis(); // 设置Eureka Server节点启动时间
        if (count > 0) { // 当从其它集群节点拉取的实例数大于0时，将peerInstancesTransferEmptyOnStartup设置为false
            this.peerInstancesTransferEmptyOnStartup = false;
        }
        DataCenterInfo.Name selfName = applicationInfoManager.getInfo().getDataCenterInfo().getName();
        boolean isAws = Name.Amazon == selfName;
        if (isAws && serverConfig.shouldPrimeAwsReplicaConnections()) {
            logger.info("Priming AWS connections for all replicas..");
            primeAwsReplicas(applicationInfoManager);
        }
        logger.info("Changing status to UP");
        applicationInfoManager.setInstanceStatus(InstanceStatus.UP); // 设置实例的状态为UP
        super.postInit(); // 默认每隔60秒执行剔除定时任务
    }

    /**
     * Prime connections for Aws replicas.
     * <p>
     * Sometimes when the eureka servers comes up, AWS firewall may not allow
     * the network connections immediately. This will cause the outbound
     * connections to fail, but the inbound connections continue to work. What
     * this means is the clients would have switched to this node (after EIP
     * binding) and so the other eureka nodes will expire all instances that
     * have been switched because of the lack of outgoing heartbeats from this
     * instance.
     * </p>
     * <p>
     * The best protection in this scenario is to block and wait until we are
     * able to ping all eureka nodes successfully atleast once. Until then we
     * won't open up the traffic.
     * </p>
     */
    private void primeAwsReplicas(ApplicationInfoManager applicationInfoManager) {
        boolean areAllPeerNodesPrimed = false;
        while (!areAllPeerNodesPrimed) {
            String peerHostName = null;
            try {
                Application eurekaApps = this.getApplication(applicationInfoManager.getInfo().getAppName(), false);
                if (eurekaApps == null) {
                    areAllPeerNodesPrimed = true;
                    logger.info("No peers needed to prime.");
                    return;
                }
                for (PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
                    for (InstanceInfo peerInstanceInfo : eurekaApps.getInstances()) {
                        LeaseInfo leaseInfo = peerInstanceInfo.getLeaseInfo();
                        // If the lease is expired - do not worry about priming
                        if (System.currentTimeMillis() > (leaseInfo
                                .getRenewalTimestamp() + (leaseInfo
                                .getDurationInSecs() * 1000))
                                + (2 * 60 * 1000)) {
                            continue;
                        }
                        peerHostName = peerInstanceInfo.getHostName();
                        logger.info("Trying to send heartbeat for the eureka server at {} to make sure the " +
                                "network channels are open", peerHostName);
                        // Only try to contact the eureka nodes that are in this instance's registry - because
                        // the other instances may be legitimately down
                        if (peerHostName.equalsIgnoreCase(new URI(node.getServiceUrl()).getHost())) {
                            node.heartbeat(
                                    peerInstanceInfo.getAppName(),
                                    peerInstanceInfo.getId(),
                                    peerInstanceInfo,
                                    null,
                                    true);
                        }
                    }
                }
                areAllPeerNodesPrimed = true;
            } catch (Throwable e) {
                logger.error("Could not contact {}", peerHostName, e);
                try {
                    Thread.sleep(PRIME_PEER_NODES_RETRY_MS);
                } catch (InterruptedException e1) {
                    logger.warn("Interrupted while priming : ", e1);
                    areAllPeerNodesPrimed = true;
                }
            }
        }
    }

    /**
     * Checks to see if the registry access is allowed or the server is in a
     * situation where it does not all getting registry information. The server
     * does not return registry information for a period specified in
     * {@link EurekaServerConfig#getWaitTimeInMsWhenSyncEmpty()}, if it cannot
     * get the registry information from the peer eureka nodes at start up.
     *
     * @return false - if the instances count from a replica transfer returned
     *         zero and if the wait time has not elapsed, otherwise returns true
     */
    @Override
    public boolean shouldAllowAccess(boolean remoteRegionRequired) { // 判断Eureka Server是否允许被Eureka Client获取注册信息
        if (this.peerInstancesTransferEmptyOnStartup) { // 当syncUp()方法未获取到应用实例时，默认5分钟后不允许被Euerka Client获取注册信息
            if (!(System.currentTimeMillis() > this.startupTime + serverConfig.getWaitTimeInMsWhenSyncEmpty())) {
                return false;
            }
        }
        if (remoteRegionRequired) {
            for (RemoteRegionRegistry remoteRegionRegistry : this.regionNameVSRemoteRegistry.values()) {
                if (!remoteRegionRegistry.isReadyForServingData()) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean shouldAllowAccess() {
        return shouldAllowAccess(true);
    }

    /**
     * @deprecated use {@link com.netflix.eureka.cluster.PeerEurekaNodes#getPeerEurekaNodes()} directly.
     *
     * Gets the list of peer eureka nodes which is the list to replicate
     * information to.
     *
     * @return the list of replica nodes.
     */
    @Deprecated
    public List<PeerEurekaNode> getReplicaNodes() {
        return Collections.unmodifiableList(peerEurekaNodes.getPeerEurekaNodes());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.eureka.registry.InstanceRegistry#cancel(java.lang.String,
     * java.lang.String, long, boolean)
     */
    @Override
    public boolean cancel(final String appName, final String id, // 服务下线
                          final boolean isReplication) {
        if (super.cancel(appName, id, isReplication)) { // 执行父类的服务下线方法
            replicateToPeers(Action.Cancel, appName, id, null, null, isReplication); // 集群同步
            synchronized (lock) { // 服务下线动态增加计数
                if (this.expectedNumberOfClientsSendingRenews > 0) {
                    // Since the client wants to cancel it, reduce the number of clients to send renews
                    this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews - 1;
                    updateRenewsPerMinThreshold(); // 更新自我保护阈值
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Registers the information about the {@link InstanceInfo} and replicates
     * this information to all peer eureka nodes. If this is replication event
     * from other replica nodes then it is not replicated.
     *
     * @param info
     *            the {@link InstanceInfo} to be registered and replicated.
     * @param isReplication
     *            true if this is a replication event from other replica nodes,
     *            false otherwise.
     */
    @Override
    public void register(final InstanceInfo info, final boolean isReplication) { // 服务注册
        int leaseDuration = Lease.DEFAULT_DURATION_IN_SECS; // 租约过期时间，默认为90秒，及当服务端超过90s没有收到客户端的心跳，则主动剔除该节点
        if (info.getLeaseInfo() != null && info.getLeaseInfo().getDurationInSecs() > 0) {
            leaseDuration = info.getLeaseInfo().getDurationInSecs(); // 当客户端自己定义了心跳超时时间，则采用客户端的时间
        }
        super.register(info, leaseDuration, isReplication); // 注册应用实例信息
        replicateToPeers(Action.Register, info.getAppName(), info.getId(), info, null, isReplication); // 发起集群同步操作，将实例信息同步到Eureka Server集群中的其它节点上
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.eureka.registry.InstanceRegistry#renew(java.lang.String,
     * java.lang.String, long, boolean)
     */
    public boolean renew(final String appName, final String id, final boolean isReplication) { // 服务续约
        if (super.renew(appName, id, isReplication)) { // 执行续约操作（更新续约时间）
            replicateToPeers(Action.Heartbeat, appName, id, null, null, isReplication); // 同步到Eureka Server集群节点
            return true;
        }
        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.eureka.registry.InstanceRegistry#statusUpdate(java.lang.String,
     * java.lang.String, com.netflix.appinfo.InstanceInfo.InstanceStatus,
     * java.lang.String, boolean)
     */
    @Override
    public boolean statusUpdate(final String appName, final String id,
                                final InstanceStatus newStatus, String lastDirtyTimestamp,
                                final boolean isReplication) {
        if (super.statusUpdate(appName, id, newStatus, lastDirtyTimestamp, isReplication)) {
            replicateToPeers(Action.StatusUpdate, appName, id, null, newStatus, isReplication);
            return true;
        }
        return false;
    }

    @Override
    public boolean deleteStatusOverride(String appName, String id,
                                        InstanceStatus newStatus,
                                        String lastDirtyTimestamp,
                                        boolean isReplication) {
        if (super.deleteStatusOverride(appName, id, newStatus, lastDirtyTimestamp, isReplication)) {
            replicateToPeers(Action.DeleteStatusOverride, appName, id, null, null, isReplication);
            return true;
        }
        return false;
    }

    /**
     * Replicate the <em>ASG status</em> updates to peer eureka nodes. If this
     * event is a replication from other nodes, then it is not replicated to
     * other nodes.
     *
     * @param asgName the asg name for which the status needs to be replicated.
     * @param newStatus the {@link ASGStatus} information that needs to be replicated.
     * @param isReplication true if this is a replication event from other nodes, false otherwise.
     */
    @Override
    public void statusUpdate(final String asgName, final ASGStatus newStatus, final boolean isReplication) {
        // If this is replicated from an other node, do not try to replicate again.
        if (isReplication) {
            return;
        }
        for (final PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
            replicateASGInfoToReplicaNodes(asgName, newStatus, node);

        }
    }

    @Override
    public boolean isLeaseExpirationEnabled() {
        if (!isSelfPreservationModeEnabled()) { // 是否关闭自我保护，默认不关闭
            // The self preservation mode is disabled, hence allowing the instances to expire.
            return true;
        }
        return numberOfRenewsPerMinThreshold > 0 && getNumOfRenewsInLastMin() > numberOfRenewsPerMinThreshold; // 判断最后一分钟收到的续约数量是否大于numberOfRenewsPerMinThreshold最小续约数阈值
    }

    /**
     * Checks to see if the self-preservation mode is enabled.
     *
     * <p>
     * The self-preservation mode is enabled if the expected number of renewals
     * per minute {@link #getNumOfRenewsInLastMin()} is lesser than the expected
     * threshold which is determined by {@link #getNumOfRenewsPerMinThreshold()}
     * . Eureka perceives this as a danger and stops expiring instances as this
     * is most likely because of a network event. The mode is disabled only when
     * the renewals get back to above the threshold or if the flag
     * {@link EurekaServerConfig#shouldEnableSelfPreservation()} is set to
     * false.
     * </p>
     *
     * @return true if the self-preservation mode is enabled, false otherwise.
     */
    @Override
    public boolean isSelfPreservationModeEnabled() {
        return serverConfig.shouldEnableSelfPreservation();
    }

    @Override
    public InstanceInfo getNextServerFromEureka(String virtualHostname, boolean secure) {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * Updates the <em>renewal threshold</em> based on the current number of
     * renewals. The threshold is a percentage as specified in
     * {@link EurekaServerConfig#getRenewalPercentThreshold()} of renewals
     * received per minute {@link #getNumOfRenewsInLastMin()}.
     */
    private void updateRenewalThreshold() { // 定时更新客户端数量的逻辑，默认每隔15分钟更新每分钟最小续约数量阈值，用来判断是否需要开启自我保护机制
        try {
            Applications apps = eurekaClient.getApplications(); // 获取本地Applications缓存
            int count = 0; // 本地实例数量
            for (Application app : apps.getRegisteredApplications()) {
                for (InstanceInfo instance : app.getInstances()) {
                    if (this.isRegisterable(instance)) {
                        ++count;
                    }
                }
            }
            synchronized (lock) {
                // Update threshold only if the threshold is greater than the
                // current expected threshold or if self preservation is disabled.
                if ((count) > (serverConfig.getRenewalPercentThreshold() * expectedNumberOfClientsSendingRenews)
                        || (!this.isSelfPreservationModeEnabled())) {
                    this.expectedNumberOfClientsSendingRenews = count;
                    updateRenewsPerMinThreshold(); // 更新自我保护阈值
                }
            }
            logger.info("Current renewal threshold is : {}", numberOfRenewsPerMinThreshold);
        } catch (Throwable e) {
            logger.error("Cannot update renewal threshold", e);
        }
    }

    /**
     * Gets the list of all {@link Applications} from the registry in sorted
     * lexical order of {@link Application#getName()}.
     *
     * @return the list of {@link Applications} in lexical order.
     */
    @Override
    public List<Application> getSortedApplications() {
        List<Application> apps = new ArrayList<Application>(getApplications().getRegisteredApplications());
        Collections.sort(apps, APP_COMPARATOR);
        return apps;
    }

    /**
     * Gets the number of <em>renewals</em> in the last minute.
     *
     * @return a long value representing the number of <em>renewals</em> in the last minute.
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfReplicationsInLastMin",
            description = "Number of total replications received in the last minute",
            type = com.netflix.servo.annotations.DataSourceType.GAUGE)
    public long getNumOfReplicationsInLastMin() {
        return numberOfReplicationsLastMin.getCount();
    }

    /**
     * Checks if the number of renewals is lesser than threshold.
     *
     * @return 0 if the renewals are greater than threshold, 1 otherwise.
     */
    @com.netflix.servo.annotations.Monitor(name = "isBelowRenewThreshold", description = "0 = false, 1 = true",
            type = com.netflix.servo.annotations.DataSourceType.GAUGE)
    @Override
    public int isBelowRenewThresold() {
        if ((getNumOfRenewsInLastMin() <= numberOfRenewsPerMinThreshold)
                &&
                ((this.startupTime > 0) && (System.currentTimeMillis() > this.startupTime + (serverConfig.getWaitTimeInMsWhenSyncEmpty())))) {
            return 1;
        } else {
            return 0;
        }
    }

    /**
     * Checks if an instance is registerable in this region. Instances from other regions are rejected.
     *
     * @param instanceInfo  th instance info information of the instance
     * @return true, if it can be registered in this server, false otherwise.
     */
    public boolean isRegisterable(InstanceInfo instanceInfo) { // 检查实例是否可以注册
        DataCenterInfo datacenterInfo = instanceInfo.getDataCenterInfo();
        String serverRegion = clientConfig.getRegion();
        if (AmazonInfo.class.isInstance(datacenterInfo)) {
            AmazonInfo info = AmazonInfo.class.cast(instanceInfo.getDataCenterInfo());
            String availabilityZone = info.get(MetaDataKey.availabilityZone);
            // Can be null for dev environments in non-AWS data center
            if (availabilityZone == null && US_EAST_1.equalsIgnoreCase(serverRegion)) {
                return true;
            } else if ((availabilityZone != null) && (availabilityZone.contains(serverRegion))) {
                // If in the same region as server, then consider it registerable
                return true;
            }
        }
        return true; // Everything non-amazon is registrable.
    }

    /**
     * Replicates all eureka actions to peer eureka nodes except for replication
     * traffic to this node.
     *
     */
    private void replicateToPeers(Action action, String appName, String id, // 集群同步
                                  InstanceInfo info /* optional */,
                                  InstanceStatus newStatus /* optional */, boolean isReplication) {
        Stopwatch tracer = action.getTimer().start();
        try {
            if (isReplication) { // 判断当前是否为集群同步请求，如果是，则记录最后一分钟的同步次数
                numberOfReplicationsLastMin.increment();
            }
            // If it is a replication already, do not replicate again as this will create a poison replication
            if (peerEurekaNodes == Collections.EMPTY_LIST || isReplication) { // 当集群节点为空 或 是由Eureak Server发起的请求（避免同步死循环），则不进行集群同步
                return;
            }

            for (final PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) { // 获得集群中的所有节点，逐个发起注册
                // If the url represents this host, do not replicate to yourself.
                if (peerEurekaNodes.isThisMyUrl(node.getServiceUrl())) { // 过滤自身节点
                    continue;
                }
                replicateInstanceActionsToPeers(action, appName, id, info, newStatus, node); // 发起同步请求，同步到集群节点
            }
        } finally {
            tracer.stop();
        }
    }

    /**
     * Replicates all instance changes to peer eureka nodes except for
     * replication traffic to this node.
     *
     */
    private void replicateInstanceActionsToPeers(Action action, String appName, // 发起服务同步请求，同步到集群节点
                                                 String id, InstanceInfo info, InstanceStatus newStatus,
                                                 PeerEurekaNode node) {
        try {
            InstanceInfo infoFromRegistry = null;
            CurrentRequestVersion.set(Version.V2);
            switch (action) {
                case Cancel: // 同步操作类型：下线
                    node.cancel(appName, id); // 处理下线同步操作
                    break;
                case Heartbeat: // 同步操作类型：心跳续约
                    InstanceStatus overriddenStatus = overriddenInstanceStatusMap.get(id);
                    infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                    node.heartbeat(appName, id, infoFromRegistry, overriddenStatus, false); // 处理续约同步操作
                    break;
                case Register: // 同步操作类型：注册
                    node.register(info); // 处理注册同步操作
                    break;
                case StatusUpdate: // 同步操作类型：设置覆盖状态
                    infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                    node.statusUpdate(appName, id, newStatus, infoFromRegistry); // 处理设置覆盖状态同步操作
                    break;
                case DeleteStatusOverride: // 同步操作类型：删除覆盖状态
                    infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                    node.deleteStatusOverride(appName, id, infoFromRegistry); // 处理删除覆盖状态同步操作
                    break;
            }
        } catch (Throwable t) {
            logger.error("Cannot replicate information to {} for action {}", node.getServiceUrl(), action.name(), t);
        }
    }

    /**
     * Replicates all ASG status changes to peer eureka nodes except for
     * replication traffic to this node.
     */
    private void replicateASGInfoToReplicaNodes(final String asgName,
                                                final ASGStatus newStatus, final PeerEurekaNode node) {
        CurrentRequestVersion.set(Version.V2);
        try {
            node.statusUpdate(asgName, newStatus);
        } catch (Throwable e) {
            logger.error("Cannot replicate ASG status information to {}", node.getServiceUrl(), e);
        }
    }

    @Override
    @com.netflix.servo.annotations.Monitor(name = "localRegistrySize",
            description = "Current registry size", type = DataSourceType.GAUGE)
    public long getLocalRegistrySize() {
        return super.getLocalRegistrySize();
    }
}
