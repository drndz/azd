package org.qypp;

import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.network.models.LoadBalancer;
import com.azure.resourcemanager.network.models.LoadBalancerBackend;
import com.azure.resourcemanager.network.models.LoadBalancerPublicFrontend;
import com.azure.resourcemanager.network.models.LoadBalancingRule;
import com.azure.resourcemanager.network.models.NetworkInterface;
import com.azure.resourcemanager.network.models.NicIpConfiguration;
import com.azure.resourcemanager.network.models.PublicIpAddress;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.azure.resourcemanager.trafficmanager.TrafficManager;
import com.azure.resourcemanager.trafficmanager.models.TrafficManagerProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Produces human-readable output for the configured demo resources.
 *
 * <p>This class is not executable by itself. {@link AzureInfraTool} constructs it after
 * authentication and calls it on every normal run. It prints the configured
 * resource group and Traffic Manager state to the console, then writes both:</p>
 *
 * <ul>
 *     <li>{@code target/azure-demo-graph.md}</li>
 *     <li>{@code target/azure-demo-graph.html}</li>
 * </ul>
 *
 * <p>The graph is generated from live Azure resource state visible to the
 * service principal.</p>
 */
public class AzureRepresentationGenerator {
    private final AzureResourceManager azure;
    private final TrafficManager trafficManager;
    private final Properties config;

    public AzureRepresentationGenerator(
            AzureResourceManager azure,
            TrafficManager trafficManager,
            Properties config) {
        this.azure = azure;
        this.trafficManager = trafficManager;
        this.config = config;
    }

    public void listConfiguredDemoResources() {
        String resourceGroupName = AzureConfig.required(config, "azure_resource_group");
        String trafficManagerProfileName = AzureConfig.required(config, "azure_traffic_manager_profile_name");

        System.out.println("Configured demo resources:");

        ResourceGroup resourceGroup = AzureLookup.resourceGroupOrNull(azure, resourceGroupName);
        if (resourceGroup == null) {
            System.out.printf("- resource group: %s (not found)%n%n", resourceGroupName);
            return;
        }

        System.out.printf("- resource group: %s / region=%s%n",
                resourceGroup.name(),
                resourceGroup.regionName());

        TrafficManagerProfile trafficManagerProfile = AzureLookup.trafficManagerProfileOrNull(
                trafficManager,
                resourceGroupName,
                trafficManagerProfileName);
        if (trafficManagerProfile == null) {
            System.out.printf("- Traffic Manager: %s (not found)%n%n", trafficManagerProfileName);
            return;
        }

        System.out.printf("- Traffic Manager: %s / fqdn=%s / routing=%s / monitor=%s / ttl=%d%n",
                trafficManagerProfile.name(),
                trafficManagerProfile.fqdn(),
                trafficManagerProfile.trafficRoutingMethod(),
                trafficManagerProfile.monitorStatus(),
                trafficManagerProfile.timeToLive());

        System.out.printf("- monitor: http://<endpoint>:%d%s%n",
                trafficManagerProfile.monitoringPort(),
                trafficManagerProfile.monitoringPath());

        System.out.println("- external endpoints:");
        trafficManagerProfile.externalEndpoints().values().forEach(endpoint ->
                System.out.printf("  - %s -> %s / enabled=%s / monitor=%s / weight=%d%n",
                        endpoint.name(),
                        endpoint.fqdn(),
                        endpoint.isEnabled(),
                        endpoint.monitorStatus(),
                        endpoint.routingWeight()));
        System.out.println();
    }

    public void writeConfiguredDemoGraph() throws IOException {
        String resourceGroupName = AzureConfig.required(config, "azure_resource_group");
        String trafficManagerProfileName = AzureConfig.required(config, "azure_traffic_manager_profile_name");
        TrafficManagerProfile trafficManagerProfile = AzureLookup.trafficManagerProfileOrNull(
                trafficManager,
                resourceGroupName,
                trafficManagerProfileName);

        Files.createDirectories(Path.of("target"));
        Path graphPath = Path.of("target", "azure-demo-graph.md");
        Path graphHtmlPath = Path.of("target", "azure-demo-graph.html");

        String mermaid = buildMermaid(resourceGroupName, trafficManagerProfileName, trafficManagerProfile);
        String markdown = "# Azure Demo Resource Graph\n\n```mermaid\n" + mermaid + "```\n";
        Files.writeString(graphPath, markdown, StandardCharsets.UTF_8);
        Files.writeString(graphHtmlPath, graphHtml(mermaid), StandardCharsets.UTF_8);
        System.out.printf("Wrote Azure resource graph: %s%n%n", graphPath.toAbsolutePath());
        System.out.printf("Wrote Azure resource graph HTML: %s%n%n", graphHtmlPath.toAbsolutePath());
    }

    private String buildMermaid(
            String resourceGroupName,
            String trafficManagerProfileName,
            TrafficManagerProfile trafficManagerProfile) {
        ResourceGroup resourceGroup = AzureLookup.resourceGroupOrNull(azure, resourceGroupName);
        StringBuilder mermaid = new StringBuilder();
        mermaid.append("flowchart TD\n");
        mermaid.append("    client[Client DNS query]\n");
        mermaid.append("    sub[Azure subscription<br/>")
                .append(escapeMermaid(AzureConfig.required(config, "azure_subscription_id")))
                .append("]\n");
        mermaid.append("    rg[Resource group<br/>")
                .append(escapeMermaid(resourceGroupName))
                .append("]\n");

        if (resourceGroup != null) {
            mermaid.append("    rgLocation[Region<br/>")
                    .append(escapeMermaid(resourceGroup.regionName()))
                    .append("]\n");
        }

        if (trafficManagerProfile == null) {
            mermaid.append("    tmMissing[Traffic Manager profile missing<br/>")
                    .append(escapeMermaid(trafficManagerProfileName))
                    .append("]\n");
        } else {
            mermaid.append("    tm[Traffic Manager<br/>")
                    .append(escapeMermaid(trafficManagerProfile.name()))
                    .append("<br/>")
                    .append(escapeMermaid(trafficManagerProfile.fqdn()))
                    .append("<br/>")
                    .append(escapeMermaid(String.valueOf(trafficManagerProfile.monitorStatus())))
                    .append("]\n");

            int endpointIndex = 1;
            Map<String, LoadBalancer> loadBalancersById = loadBalancersById();
            for (var endpoint : trafficManagerProfile.externalEndpoints().values()) {
                String endpointNode = "ep" + endpointIndex;
                mermaid.append("    ")
                        .append(endpointNode)
                        .append("[External endpoint<br/>")
                        .append(escapeMermaid(endpoint.name()))
                        .append("<br/>")
                        .append(escapeMermaid(endpoint.fqdn()))
                        .append("<br/>monitor=")
                        .append(escapeMermaid(String.valueOf(endpoint.monitorStatus())))
                        .append("]\n");
                mermaid.append("    tm -->|weighted DNS answer| ")
                        .append(endpointNode)
                        .append("\n");

                LoadBalancer loadBalancer = findLoadBalancerByExternalTarget(endpoint.fqdn(), loadBalancersById);
                if (loadBalancer != null) {
                    appendLoadBalancerGraph(mermaid, endpointNode, endpointIndex, loadBalancer);
                }
                endpointIndex++;
            }
        }

        mermaid.append("    client --> tm\n");
        mermaid.append("    sub --> rg\n");
        if (resourceGroup != null) {
            mermaid.append("    rg --> rgLocation\n");
        }
        if (trafficManagerProfile == null) {
            mermaid.append("    rg --> tmMissing\n");
        } else {
            mermaid.append("    rg --> tm\n");
        }
        return mermaid.toString();
    }

    private void appendLoadBalancerGraph(
            StringBuilder mermaid,
            String endpointNode,
            int endpointIndex,
            LoadBalancer loadBalancer) {
        String lbNode = "lb" + endpointIndex;
        mermaid.append("    ")
                .append(lbNode)
                .append("[Azure Load Balancer<br/>")
                .append(escapeMermaid(loadBalancer.name()))
                .append("<br/>sku=")
                .append(escapeMermaid(loadBalancerSku(loadBalancer)))
                .append("]\n");
        mermaid.append("    ")
                .append(endpointNode)
                .append(" -->|public endpoint| ")
                .append(lbNode)
                .append("\n");

        int frontendIndex = 1;
        for (LoadBalancerPublicFrontend frontend : loadBalancer.publicFrontends().values()) {
            PublicIpAddress publicIp = frontend.publicIpAddressId() == null
                    ? null
                    : azure.publicIpAddresses().getById(frontend.publicIpAddressId());
            String frontendNode = lbNode + "_fe" + frontendIndex;
            mermaid.append("    ")
                    .append(frontendNode)
                    .append("[Public frontend<br/>")
                    .append(escapeMermaid(frontend.name()));
            if (publicIp != null) {
                mermaid.append("<br/>").append(escapeMermaid(publicIp.ipAddress()));
            }
            mermaid.append("]\n");
            mermaid.append("    ").append(lbNode).append(" --> ").append(frontendNode).append("\n");
            frontendIndex++;
        }

        int ruleIndex = 1;
        for (LoadBalancingRule rule : loadBalancer.loadBalancingRules().values()) {
            String ruleNode = lbNode + "_rule" + ruleIndex;
            mermaid.append("    ")
                    .append(ruleNode)
                    .append("[LB rule<br/>")
                    .append(escapeMermaid(rule.name()))
                    .append("<br/>")
                    .append(escapeMermaid(String.valueOf(rule.protocol())))
                    .append(" ")
                    .append(rule.frontendPort())
                    .append(" -> ")
                    .append(rule.backendPort())
                    .append("]\n");
            mermaid.append("    ").append(lbNode).append(" --> ").append(ruleNode).append("\n");
            ruleIndex++;
        }

        int backendIndex = 1;
        for (LoadBalancerBackend backend : loadBalancer.backends().values()) {
            String backendNode = lbNode + "_be" + backendIndex;
            mermaid.append("    ")
                    .append(backendNode)
                    .append("[Backend pool<br/>")
                    .append(escapeMermaid(backend.name()))
                    .append("]\n");
            mermaid.append("    ").append(lbNode).append(" --> ").append(backendNode).append("\n");
            appendBackendMembers(mermaid, backendNode, backend);
            backendIndex++;
        }
    }

    private void appendBackendMembers(StringBuilder mermaid, String backendNode, LoadBalancerBackend backend) {
        Map<String, String> nicIpConfigs = backend.backendNicIPConfigurationNames();
        Set<String> vmNames = new LinkedHashSet<>();
        for (String vmId : backend.getVirtualMachineIds()) {
            vmNames.add(resourceNameFromId(vmId));
        }
        Set<String> linkedVmNames = new LinkedHashSet<>();

        int nicIndex = 1;
        for (Map.Entry<String, String> entry : nicIpConfigs.entrySet()) {
            String nicId = entry.getKey();
            String ipConfigName = entry.getValue();
            NetworkInterface nic = azure.networkInterfaces().getById(nicId);
            String nicName = nic == null ? resourceNameFromId(nicId) : nic.name();
            NicIpConfiguration ipConfig = nic == null ? null : nic.ipConfigurations().get(ipConfigName);
            String nicNode = backendNode + "_nic" + nicIndex;

            mermaid.append("    ")
                    .append(nicNode)
                    .append("[NIC<br/>")
                    .append(escapeMermaid(nicName));
            if (ipConfig != null) {
                mermaid.append("<br/>")
                        .append(escapeMermaid(ipConfig.privateIpAddress()));
            }
            mermaid.append("]\n");
            mermaid.append("    ").append(backendNode).append(" --> ").append(nicNode).append("\n");

            String matchingVmName = matchingVmName(nicName, vmNames);
            if (matchingVmName != null) {
                String vmNode = backendNode + "_vm" + nodeId(matchingVmName);
                appendVmNode(mermaid, vmNode, matchingVmName);
                mermaid.append("    ").append(nicNode).append(" --> ").append(vmNode).append("\n");
                linkedVmNames.add(matchingVmName);
            }
            nicIndex++;
        }

        for (String vmName : vmNames) {
            if (linkedVmNames.contains(vmName)) {
                continue;
            }
            String vmNode = backendNode + "_vm" + nodeId(vmName);
            appendVmNode(mermaid, vmNode, vmName);
            mermaid.append("    ").append(backendNode).append(" --> ").append(vmNode).append("\n");
        }
    }

    private static void appendVmNode(StringBuilder mermaid, String vmNode, String vmName) {
        mermaid.append("    ")
                .append(vmNode)
                .append("[VM<br/>")
                .append(escapeMermaid(vmName))
                .append("]\n");
    }

    private LoadBalancer findLoadBalancerByExternalTarget(
            String target,
            Map<String, LoadBalancer> loadBalancersById) {
        String normalizedTarget = normalizeName(target);
        for (LoadBalancer loadBalancer : loadBalancersById.values()) {
            for (LoadBalancerPublicFrontend frontend : loadBalancer.publicFrontends().values()) {
                String publicIpId = frontend.publicIpAddressId();
                PublicIpAddress publicIp = publicIpId == null ? null : azure.publicIpAddresses().getById(publicIpId);
                if (publicIp == null) {
                    continue;
                }
                if (Objects.equals(normalizedTarget, normalizeName(publicIp.ipAddress()))
                        || Objects.equals(normalizedTarget, normalizeName(publicIp.fqdn()))
                        || Objects.equals(normalizedTarget, normalizeName(publicIp.leafDomainLabel()))) {
                    return loadBalancer;
                }
            }
        }
        return null;
    }

    private Map<String, LoadBalancer> loadBalancersById() {
        Map<String, LoadBalancer> loadBalancers = new LinkedHashMap<>();
        azure.loadBalancers().list().forEach(loadBalancer ->
                loadBalancers.put(normalizeId(loadBalancer.id()), loadBalancer));
        return loadBalancers;
    }

    private static String loadBalancerSku(LoadBalancer loadBalancer) {
        if (loadBalancer.sku() == null || loadBalancer.sku().sku() == null || loadBalancer.sku().sku().name() == null) {
            return "unknown";
        }
        return loadBalancer.sku().sku().name().toString();
    }

    private static String matchingVmName(String nicName, Set<String> vmNames) {
        String normalizedNicName = normalizeName(nicName);
        for (String vmName : vmNames) {
            if (normalizedNicName.contains(normalizeName(vmName))) {
                return vmName;
            }
        }
        return null;
    }

    private static String resourceNameFromId(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return "unknown";
        }
        String trimmed = resourceId.trim();
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String nodeId(String value) {
        return normalizeName(value).replaceAll("[^a-z0-9_]", "_");
    }

    private static String escapeMermaid(String value) {
        return value.replace("\"", "'")
                .replace("[", "(")
                .replace("]", ")")
                .replace("{", "(")
                .replace("}", ")")
                .replace("|", "/");
    }

    private static String graphHtml(String mermaid) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>Azure Demo Resource Graph</title>
                    <style>
                        :root {
                            color-scheme: light;
                            --bg: #f6f8fb;
                            --panel: #ffffff;
                            --text: #172033;
                            --muted: #5d6b82;
                            --border: #d9e1ec;
                        }
                        * { box-sizing: border-box; }
                        body {
                            margin: 0;
                            min-height: 100vh;
                            font-family: Segoe UI, Arial, sans-serif;
                            color: var(--text);
                            background: var(--bg);
                        }
                        header {
                            padding: 24px 32px 12px;
                            border-bottom: 1px solid var(--border);
                            background: var(--panel);
                        }
                        h1 {
                            margin: 0 0 6px;
                            font-size: 24px;
                            font-weight: 650;
                        }
                        .meta {
                            margin: 0;
                            color: var(--muted);
                            font-size: 14px;
                        }
                        main {
                            padding: 24px 32px 32px;
                        }
                        .graph {
                            width: 100%;
                            overflow: auto;
                            padding: 24px;
                            border: 1px solid var(--border);
                            border-radius: 8px;
                            background: var(--panel);
                        }
                        .mermaid {
                            min-width: 720px;
                        }
                    </style>
                </head>
                <body>
                    <header>
                        <h1>Azure Demo Resource Graph</h1>
                        <p class="meta">Generated from the live Azure resources visible to the Java utility.</p>
                    </header>
                    <main>
                        <section class="graph">
                            <pre class="mermaid">
                __MERMAID_GRAPH__
                            </pre>
                        </section>
                    </main>
                    <script type="module">
                        import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';
                        mermaid.initialize({
                            startOnLoad: true,
                            theme: 'base',
                            securityLevel: 'strict',
                            themeVariables: {
                                primaryColor: '#e7f0ff',
                                primaryTextColor: '#172033',
                                primaryBorderColor: '#5087d9',
                                lineColor: '#53657d',
                                secondaryColor: '#eef7f1',
                                tertiaryColor: '#fff7e8',
                                fontFamily: 'Segoe UI, Arial, sans-serif'
                            }
                        });
                    </script>
                </body>
                </html>
                """.replace("__MERMAID_GRAPH__", escapeHtml(mermaid));
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
