/*
 * Copyright 2017-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spm.app;

import org.apache.felix.scr.annotations.*;
import org.onlab.packet.MacAddress;
import org.onosproject.app.ApplicationAdminService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.event.Event;
import org.onosproject.net.*;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.device.PortStatistics;
import org.onosproject.net.flow.*;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.EthCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;

import org.onosproject.net.link.LinkEvent;
import org.onosproject.net.topology.TopologyEvent;
import org.onosproject.net.topology.TopologyListener;
import org.onosproject.net.topology.TopologyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static org.onosproject.net.flow.criteria.Criterion.Type.ETH_DST;
import static org.onosproject.net.flow.criteria.Criterion.Type.ETH_SRC;

/**
 * <h1>Intent-Based Proactive Load-Balancing ONOS application</h1>
 *
 * @author Adeel Rafiq @JEJUNU
 * @version 1.0
 * @since 2019
 */
@Component(immediate = true)
@Service
public class SmartPathManager implements ServiceSPM {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ApplicationAdminService applicationAdminService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowObjectiveService flowObjectiveService;

    private final TopologyListener topologyListener = new InternalTopologyListener();

    private ApplicationId appId;
    private ApplicationId onosforwarding;

    private int DEFAULT_RULE_PRIO = 50000;
    private final int LOCAL_RULE_PRIO = 60000;
    // This determines the minium time between executions of the global path optimization procedure

    // Decide if weather or not to stop the onos forwarding app to begin of this app.
    // (Will be started again after exiting this app)
    private final boolean deactivate_onos_app = true;

    // Test-Scenario (App I => scenario nr. 2 and APP II => scenario nr. 4 //
    // Test-scenarios:
    // 1> Hopcount only
    // 2> Flowcount + Hopcount
    // 3> UsagePercent + FlowCount + Hopcount
    // 4> Cap - Load + Flowcount + Hopcount
    private final int test_scenario = 4;

    @Activate
    protected void activate() {

        String appname = "org.spm.app";
        appId = coreService.registerApplication(appname);
        onosforwarding = coreService.getAppId("org.onosproject.fwd");

        if (deactivate_onos_app) {
            /* deactivate org.onos.fwd, only if it is appropriate */
            try {
                applicationAdminService.deactivate(onosforwarding);
                log.info("### Deactivating Onos Reactive Forwarding App ###");
            } catch (NullPointerException ne) {
                log.info(ne.getMessage());
            }
        }

        topologyService.addListener(topologyListener);

        log.info("### Started " + appId.name() + " with id " + appId.id() + " ###");
    }

    @Deactivate
    protected void deactivate() {

        try {
            log.info("### Activating ONOS Reactive Forwarding App ###");
            applicationAdminService.activate(onosforwarding);
        } catch (NullPointerException ne) {
            log.info(ne.getMessage());
        }

        flowRuleService.removeFlowRulesById(appId);
        log.info("### Stopped and removed flowrules. ###");
    }

    @Override
    public void intentCreate(IntentSPM intentSPM) {

        log.info("### Setting local Flows for Host: " + intentSPM.getHostMac() + " ###");
        setFlow(DeviceId.deviceId(intentSPM.getSwitchID()), null, MacAddress.valueOf(intentSPM.getHostMac()), PortNumber.portNumber(intentSPM.getIngressPort()), LOCAL_RULE_PRIO);

        deployBestShortestPath(MacAddress.valueOf(intentSPM.getHostMac()), DeviceId.deviceId(intentSPM.getSwitchID()));
    }

    @Override
    public void intentDelete(IntentSPM intentSPM) {

        cleanFlowRules(MacAddress.valueOf(intentSPM.getHostMac()));
    }

    public void deployBestShortestPath(MacAddress srcHostMac, DeviceId srcDeviceId) {

        log.info("### Hostcount: " + hostService.getHostCount() + " ###");

        /* Querying Known Hosts from ONOS hostsevice **/
        Iterable<Host> hostIterable = hostService.getHosts();
        /* Converting Iterable to ArrayList **/
        List<Host> hostList = new ArrayList<>();
        hostIterable.forEach(hostList::add);

        /* Iterate through all hosts, find paths between hosts, find best paths, deploy paths **/
        if (hostList.size() > 1)
            for (int a = 0; a < hostList.size(); a++) {

                Host dstHost = hostList.get(a);
                DeviceId dstDeviceId = dstHost.location().deviceId();

                /* exclude A==B combinations & ...**/
                /*  exclude Host-AB-pairs connected to the same switch **/
                if (!dstDeviceId.toString().equalsIgnoreCase(srcDeviceId.toString())) {

                    // Deploy FlowRules for Flow (A->B,B->A)

                    if (flowRuleExists(srcHostMac, dstHost.mac())) {
                        log.info("### Flow already exists! Not deploying Flow-Rules for Host-combo (" + srcHostMac.toString() + ":" + dstHost.mac().toString() + ") again! ");
                        return;
                    }

                    log.info("Evaluating paths between host a and b");
                    Set<Path> ps = topologyService.getPaths(topologyService.currentTopology(), srcDeviceId, dstDeviceId);
                    log.info("Path-size, by normal service:: " + ps.size());

                    Stream<Path> pstream = topologyService.getKShortestPaths(topologyService.currentTopology(), srcDeviceId, dstDeviceId);

                    deployBestShortestPath(pstream, srcHostMac, dstHost.mac());
                }
            }
        else
            log.info("### There is only " + hostList.size() + " hosts, nothing to do. ###");
    }

    public void deployBestShortestPath(Stream<Path> pstream, MacAddress srcMac, MacAddress dstMac) {

        //Convert a Stream to Set
        Set<Path> pathSet = pstream.collect(Collectors.toSet());

        log.info("K-Path-size: " + pathSet.size());

        Path bestPath = bestPathInSet(pathSet);

        if (bestPath == null)
            log.info("### I could not find a path between  " + srcMac + " and " + dstMac + " ###");
        else {

            setFLows(bestPath.links(), srcMac, dstMac);
        }
    }

    public boolean flowRuleExists(MacAddress srcMac, MacAddress dstMac) {

        // This block searches for already exiting flow rules for this host-pair
        for (FlowRule flowRule : flowRuleService.getFlowEntriesById(appId)) {
            MacAddress src = null;
            MacAddress dst = null;

            Criterion criterion = flowRule.selector().getCriterion(ETH_SRC);
            if (criterion != null)
                src = ((EthCriterion) criterion).mac();

            criterion = flowRule.selector().getCriterion(ETH_DST);
            if (criterion != null)
                dst = ((EthCriterion) criterion).mac();

            if (dst != null && src != null) {
                if (srcMac.toString().matches(src.toString()) && dstMac.toString().matches(dst.toString())) {
                    return true;
                }
                if (dstMac == dst && srcMac == src) {
                    return true;
                }
            }
        }
        return false;
    }

    public void setFLows(List<Link> linkList, MacAddress srcMac, MacAddress dstMac) {

        for (Link link : linkList) {
            log.info("### Flow-Direction: Forward (A->B) ###");
            DeviceId srcSwitch = link.src().deviceId();
            PortNumber outPort = link.src().port();
            setFlow(srcSwitch, srcMac, dstMac, outPort, DEFAULT_RULE_PRIO);

            log.info("### Flow-Direction: Forward (B->A) ###");
            DeviceId dstSwitch = link.dst().deviceId();
            outPort = link.dst().port();
            setFlow(dstSwitch, dstMac, srcMac, outPort, DEFAULT_RULE_PRIO);
        }
    }

    public void setFlow(DeviceId switchDevice, MacAddress srcMac, MacAddress dstMac, PortNumber outPort, int flowPriority) {

        /*  Define which packets(flows) to handle **/
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
        /*  if no src-Mac is defined forward disregarding source address (used in local switch rules) */
        if (srcMac != null) {
            selectorBuilder.matchEthSrc(srcMac);
        }
        selectorBuilder.matchEthDst(dstMac);

        /*  Define what to do with the packet / flow **/
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(outPort)
                .build();

        /*  add Flow-Priority, Timeout, Flags and some other meta information **/
        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selectorBuilder.build())
                .withTreatment(treatment)
                .withPriority(flowPriority)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .fromApp(appId)
                .add();

        if (srcMac == null)
            log.info("### Add Flow at Switch " + switchDevice.toString() + " dst: " + dstMac.toString() + " outPort: " + outPort.toString() + " ###");
        if (srcMac != null)
            log.info("### Add Flow at Switch " + switchDevice.toString() + " src: " + srcMac.toString() + " dst: " + dstMac.toString() + " outPort: " + outPort.toString() + " ###");

        flowObjectiveService.forward(switchDevice, forwardingObjective);
    }

    public Path bestPathInSet(Set<Path> paths) {
        log.info("Starting now");
        // If the Set is empty, return null
        int count_paths = paths.size();

        if (count_paths == 0)
            return null;

        Path bestPath = paths.iterator().next();

        if (count_paths == 1)
            return bestPath;

        double[] maxLinkUsage = new double[count_paths];
        double[] minFreeonPath = new double[count_paths];
        int[] hopcount = new int[count_paths];
        int[] numberOfFlows = new int[count_paths];
        Path[] pathList = new Path[count_paths];

        int[] secondUseCase = new int[count_paths];
        int[] thirdUseCase = new int[count_paths];
        int[] fourthUseCase = new int[count_paths];

        int i = 0;

        for (Path path : paths) {

            pathList[i] = path;
            maxLinkUsage[i] = maxUsagePercentOnPath(path); //=> The  usage percent of the link with the highest usage on the path.
            minFreeonPath[i] = minFreeOnPath(path); // => The free capacity of the link with the least free capacity on the path.
            hopcount[i] = (int) path.cost(); // => the path-hopcount metric
            numberOfFlows[i] = numberOfFlowsOnPath(path); // => The number of flows set on this path.
            log.info("Path-Log: No. " + i + ", nexthop: " + path.links().iterator().next().dst().deviceId() + ", minFree: " + minFreeonPath[i] + ", maxusage: " + maxLinkUsage[i] + ", NumberOfFLows: " + numberOfFlows[i] + ", hopcount: " + hopcount[i]);
            i++;
        }

        for (int j = 0; j < count_paths; j++) {

            secondUseCase[j] = hopcount[j] + numberOfFlows[j];
            thirdUseCase[j] = hopcount[j] + numberOfFlows[j] + (int) maxLinkUsage[j];
            fourthUseCase[j] = hopcount[j] + numberOfFlows[j] + (int) minFreeonPath[j];
        }

        //Different evaluation scenarios:
        //1: Only Hopcount
        //2: Hopcount + Flowcount
        //3: Hopcount + Flowcount + UsagePercentage
        //4: Cap - Load + Flowcount + Hopcount

        if (test_scenario == 1) {
            log.info("Path decision based on scenario 1: Only Hopcount is used !!!");
            /** PATH DECISION: compare hopcount ***/

            if (smallestNumberFromArray(hopcount) == -1) {
                // -1 => All Paths have the same amount of hops.
                log.info("paper-log: Paths are all equal: Nexthop " + bestPath.links().get(0).dst().deviceId());
                return bestPath;
            } else // NumberOfFlowRules are equal, but hopcount is different:
            {
                log.info("hopcount is different.");
                int index = smallestNumberFromArray(hopcount);
                bestPath = pathList[index];
                log.info("paper-log: Bestpath: " + bestPath.toString());
                return bestPath;
            }
        }

        if (test_scenario == 2) {
            log.info("Path decision based on scenario 2: Flowcount + Hopcount is used !!!");

            int index = smallestNumberFromArray(secondUseCase);
            bestPath = pathList[index];
            log.info("paper-log: Bestpath: " + bestPath.toString());
            return bestPath;
        }

        if (test_scenario == 3) {
            log.info("Path decision based on scenario 3: Using Usages, Flowcount and Hopcount !!!");

            int index = smallestNumberFromArray(thirdUseCase);
            bestPath = pathList[index];
            log.info("paper-log: Bestpath: " + bestPath.toString());
            return bestPath;
        }

        if (test_scenario == 4) {
            log.info("Path decision based on scenario 4: Using (Capacity-Load), Flowcount and Hopcount !!!");

            int index = smallestNumberFromArray(fourthUseCase);
            bestPath = pathList[index];
            log.info("paper-log: Bestpath: " + bestPath.toString());
            return bestPath;
        }

        return bestPath;
    }

    public double maxUsagePercentOnPath(Path path) {

        double maxUsage = 0;

        for (Link link : path.links()) {

            double load = loadOnLink(link);
            double capacity = capacityOfLink(link);
            double usagePercent;

            if (capacity > 0)
                usagePercent = (load * 100 / capacity);
            else
                usagePercent = 100;

            maxUsage = usagePercent;
        }

        return maxUsage;
    }

    public double minFreeOnPath(Path path) {

        double minFree = -7411.0;

        for (Link link : path.links()) {

            double load = loadOnLink(link);
            double capacity = capacityOfLink(link);

            minFree = capacity - load;
            log.info("cap: " + capacity);
            log.info("load: " + load);
        }

        return minFree;
    }

    public double loadOnLink(Link link) {

        DeviceId dst_id = link.dst().deviceId();
        DeviceId src_id = link.src().deviceId();
        Port dst_port = deviceService.getPort(link.dst());
        Port src_port = deviceService.getPort(link.src());

        PortStatistics srcPortStats = deviceService.getDeltaStatisticsForPort(src_id, src_port.number());
        PortStatistics dstPortStats = deviceService.getDeltaStatisticsForPort(dst_id, dst_port.number());

        float rcvRate = ((dstPortStats.bytesReceived() * 8));
        long rcvPktDrops = dstPortStats.packetsRxDropped();
        float sntRate = ((srcPortStats.bytesSent() * 8));
        long sntPktDrops = srcPortStats.packetsTxDropped();

        return rcvRate;
    }

    public int numberOfFlowsOnPath(Path path) {

        int flowRuleCounter = 0;

        for (Link link : path.links()) {

            /* Query FlowRules for every "Source"-Device at the links of the Path **/
            /* Querying only Destination Devices, as the only important nodes are the nexthop nodes **/
            for (FlowRule flowRule : flowRuleService.getFlowEntries(link.src().deviceId())) {

                /* If this Rule was set by this application  **/
                if (flowRule.appId() == appId.id()) {

                    /* If this Rule's Instruction is to forward on the same port as the 'path' **/
                    boolean portMatch = false;
                    //noinspection EmptyCatchBlock
                    try {
                        if (flowRule.treatment().allInstructions().get(0).toString().equals("OUTPUT:" + link.src().port().toLong())) {
                            portMatch = true;
                        }

                    } catch (NullPointerException npe) {
                        log.info("NullPointerException: " + npe.getLocalizedMessage());
                    }
                    if (portMatch) {
                        log.info("### Count: Flow-Rule at Device " + link.src().toString() + ", matches Portno: " + link.src().port().toLong());
                        flowRuleCounter++;
                    }
                }
            }
        }

        return flowRuleCounter;
    }

    public static int smallestNumberFromArray(int[] array) {

        OptionalInt max = IntStream.of(array).max();
        OptionalInt min = IntStream.of(array).min();

        int min_id = IntStream.of(array).boxed().collect(toList()).indexOf(min.getAsInt());

        // IF the smallest int is equal with the biggest one, return -1, else return index of the smallest one.
        if (max.getAsInt() == min.getAsInt()) return -1;

        else return min_id;
    }

    public int capacityOfLink(Link link) {
        // Default-Rate, if there is no reported capacity. A  mid-quality link is assumed.
        int rate = 32000000;
        ConnectPoint switchPort = link.src();

        String switchID = switchPort.deviceId().toString();
        if (switchID.contentEquals("of:00000000000003e9") || switchID.contentEquals("of:00000000000003ea") || switchID.contentEquals("of:00000000000003eb") || switchID.contentEquals("of:00000000000003ec"))
            rate = 20000000;
        if (switchID.contentEquals("of:00000000000007d1") || switchID.contentEquals("of:00000000000007d2") || switchID.contentEquals("of:00000000000007d3") || switchID.contentEquals("of:00000000000007d4") || switchID.contentEquals("of:00000000000007d5") || switchID.contentEquals("of:00000000000007d6") || switchID.contentEquals("of:00000000000007d7") || switchID.contentEquals("of:00000000000007d8"))
            rate = 10000000;
        if (switchID.contentEquals("of:0000000000000bb9") || switchID.contentEquals("of:0000000000000bba") || switchID.contentEquals("of:0000000000000bbb") || switchID.contentEquals("of:0000000000000bbc") || switchID.contentEquals("of:0000000000000bbd") || switchID.contentEquals("of:0000000000000bbe") || switchID.contentEquals("of:0000000000000bbf") || switchID.contentEquals("of:0000000000000bc0"))
            rate = 5000000;

        if (rate == 33000000)
            log.info("It seems like there was no datarate information for this mac.");
        else {
            log.info("Got capacity rate: " + rate);
        }

        return rate;
    }

    private void cleanFlowRules(MacAddress srcMac) {

        for (Device device : deviceService.getDevices()) {

            log.info("Searching for flow rules to remove from: {}", device.id());

            for (FlowEntry r : flowRuleService.getFlowEntries(device.id())) {

                boolean matchesSrc = false, matchesDst = false;

                for (Instruction i : r.treatment().allInstructions()) {
                    if (i.type() == Instruction.Type.OUTPUT) {
                        // if the flow has matching src and dst
                        for (Criterion cr : r.selector().criteria()) {
                            if (cr.type() == Criterion.Type.ETH_DST) {
                                if (((EthCriterion) cr).mac().equals(srcMac)) {
                                    matchesDst = true;
                                }
                            } else if (cr.type() == Criterion.Type.ETH_SRC) {
                                if (((EthCriterion) cr).mac().equals(srcMac)) {
                                    matchesSrc = true;
                                }
                            }
                        }
                    }
                }

                if (matchesDst || matchesSrc) {
                    log.info("Removed flow rule from device: {}", device.id());
                    flowRuleService.removeFlowRules((FlowRule) r);
                }
            }
        }
    }

    private class InternalTopologyListener implements TopologyListener {
        @Override
        public void event(TopologyEvent event) {
            List<Event> reasons = event.reasons();
            Set<HostId> hostIds = new HashSet<HostId>();

            if (reasons != null) {
                reasons.forEach(re -> {
                    if (re instanceof LinkEvent) {
                        LinkEvent le = (LinkEvent) re;
                        if (le.type() == LinkEvent.Type.LINK_REMOVED) {
                            hostIds.add(le.subject().src().hostId());
                        }
                    }
                });
            }

            bulkCleanFlowRules(hostIds);
            bulkDeployBestShortestPath(hostIds);
        }
    }

    private void bulkCleanFlowRules(Set<HostId> hostIds) {

        for (HostId hostId : hostIds) {

            cleanFlowRules(hostId.mac());
        }
    }

    private void bulkDeployBestShortestPath(Set<HostId> hostIds) {

        for (HostId hostId : hostIds) {

            deployBestShortestPath(hostId.mac(), hostService.getHost(hostId).location().deviceId());
        }
    }
}
