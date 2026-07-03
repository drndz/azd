package org.qypp;

import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.network.models.LoadBalancer;
import com.azure.resourcemanager.network.models.LoadBalancerBackend;
import com.azure.resourcemanager.network.models.LoadBalancerBackendAddress;
import com.azure.resourcemanager.network.models.LoadBalancerPublicFrontend;
import com.azure.resourcemanager.network.models.LoadBalancingRule;
import com.azure.resourcemanager.network.models.NetworkInterface;
import com.azure.resourcemanager.network.models.NicIpConfiguration;
import com.azure.resourcemanager.network.models.PublicIpAddress;
import com.azure.resourcemanager.trafficmanager.TrafficManager;
import com.azure.resourcemanager.trafficmanager.models.TrafficManagerAzureEndpoint;
import com.azure.resourcemanager.trafficmanager.models.TrafficManagerEndpoint;
import com.azure.resourcemanager.trafficmanager.models.TrafficManagerExternalEndpoint;
import com.azure.resourcemanager.trafficmanager.models.TrafficManagerProfile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Read-only recursive topology lister.
 *
 * <p>Traversal starts at the configured Traffic Manager profile, prints all
 * Traffic Manager endpoints, detects endpoints that target Azure Load Balancers,
 * then prints each matched Load Balancer's frontends, load-balancing rules,
 * backend pools, and backend members.</p>
 */
public class AzureRecursiveTopologyLister {
    private final AzureResourceManager azure;
    private final TrafficManager trafficManager;
    private final Properties config;

    public AzureRecursiveTopologyLister(
            AzureResourceManager azure,
            TrafficManager trafficManager,
            Properties config) {
        this.azure = azure;
        this.trafficManager = trafficManager;
        this.config = config;
    }

    public void listFromConfiguredTrafficManager() {
        String resourceGroupName = AzureConfig.required(config, "azure_resource_group");
        String profileName = AzureConfig.required(config, "azure_traffic_manager_profile_name");
        TrafficManagerProfile profile = AzureLookup.trafficManagerProfileOrNull(
                trafficManager,
                resourceGroupName,
                profileName);

        System.out.println("Recursive topology from configured Traffic Manager:");
        if (profile == null) {
            System.out.printf("- Traffic Manager profile not found: rg=%s / name=%s%n%n",
                    resourceGroupName,
                    profileName);
            return;
        }

        System.out.printf("- Traffic Manager: %s / fqdn=%s / routing=%s / monitor=%s%n",
                profile.name(),
                profile.fqdn(),
                profile.trafficRoutingMethod(),
                profile.monitorStatus());

        Map<String, LoadBalancer> loadBalancersById = loadBalancersById();
        if (profile.azureEndpoints().isEmpty() && profile.externalEndpoints().isEmpty()) {
            System.out.println("  - no Traffic Manager endpoints");
            System.out.println();
            return;
        }

        profile.azureEndpoints().values().forEach(endpoint ->
                listAzureTrafficManagerEndpoint(endpoint, loadBalancersById));
        profile.externalEndpoints().values().forEach(endpoint ->
                listExternalTrafficManagerEndpoint(endpoint, loadBalancersById));
        System.out.println();
    }

    private void listAzureTrafficManagerEndpoint(
            TrafficManagerAzureEndpoint endpoint,
            Map<String, LoadBalancer> loadBalancersById) {
        System.out.printf("  - TM Azure endpoint: %s / type=%s / monitor=%s / targetResourceId=%s%n",
                endpoint.name(),
                endpoint.targetResourceType(),
                endpoint.monitorStatus(),
                endpoint.targetAzureResourceId());

        LoadBalancer loadBalancer = loadBalancersById.get(normalizeId(endpoint.targetAzureResourceId()));
        if (loadBalancer == null) {
            System.out.println("    - target is not a load balancer visible to this subscription");
            return;
        }
        listLoadBalancer(loadBalancer, "    ");
    }

    private void listExternalTrafficManagerEndpoint(
            TrafficManagerExternalEndpoint endpoint,
            Map<String, LoadBalancer> loadBalancersById) {
        System.out.printf("  - TM external endpoint: %s / target=%s / monitor=%s / enabled=%s / weight=%d%n",
                endpoint.name(),
                endpoint.fqdn(),
                endpoint.monitorStatus(),
                endpoint.isEnabled(),
                endpoint.routingWeight());

        LoadBalancer loadBalancer = findLoadBalancerByExternalTarget(endpoint.fqdn(), loadBalancersById);
        if (loadBalancer == null) {
            System.out.println("    - no Azure Load Balancer matched this external target");
            return;
        }
        listLoadBalancer(loadBalancer, "    ");
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

    private void listLoadBalancer(LoadBalancer loadBalancer, String indent) {
        System.out.printf("%s- Azure Load Balancer: %s / rg=%s / region=%s / sku=%s%n",
                indent,
                loadBalancer.name(),
                loadBalancer.resourceGroupName(),
                loadBalancer.regionName(),
                loadBalancer.sku() == null || loadBalancer.sku().sku() == null
                        ? "unknown"
                        : loadBalancer.sku().sku().name());

        System.out.printf("%s  - public frontends:%n", indent);
        if (loadBalancer.publicFrontends().isEmpty()) {
            System.out.printf("%s    - none%n", indent);
        }
        loadBalancer.publicFrontends().values().forEach(frontend -> listPublicFrontend(frontend, indent + "    "));

        System.out.printf("%s  - load-balancing rules:%n", indent);
        if (loadBalancer.loadBalancingRules().isEmpty()) {
            System.out.printf("%s    - none%n", indent);
        }
        loadBalancer.loadBalancingRules().values().forEach(rule -> listLoadBalancingRule(rule, indent + "    "));

        System.out.printf("%s  - backend pools:%n", indent);
        if (loadBalancer.backends().isEmpty()) {
            System.out.printf("%s    - none%n", indent);
        }
        loadBalancer.backends().values().forEach(backend -> listBackendPool(backend, indent + "    "));
    }

    private void listPublicFrontend(LoadBalancerPublicFrontend frontend, String indent) {
        PublicIpAddress publicIp = Optional.ofNullable(frontend.publicIpAddressId())
                .map(id -> azure.publicIpAddresses().getById(id))
                .orElse(null);
        if (publicIp == null) {
            System.out.printf("%s- %s / publicIpId=%s%n",
                    indent,
                    frontend.name(),
                    frontend.publicIpAddressId());
            return;
        }

        System.out.printf("%s- %s / publicIp=%s / fqdn=%s / publicIpName=%s%n",
                indent,
                frontend.name(),
                publicIp.ipAddress(),
                publicIp.fqdn(),
                publicIp.name());
    }

    private void listLoadBalancingRule(LoadBalancingRule rule, String indent) {
        System.out.printf("%s- %s / protocol=%s / frontendPort=%d / backendPort=%d / backendPools=%s%n",
                indent,
                rule.name(),
                rule.protocol(),
                rule.frontendPort(),
                rule.backendPort(),
                rule.backends().stream().map(LoadBalancerBackend::name).toList());
    }

    private void listBackendPool(LoadBalancerBackend backend, String indent) {
        System.out.printf("%s- %s / rules=%s%n",
                indent,
                backend.name(),
                backend.loadBalancingRules().keySet());

        Map<String, String> nicIpConfigs = backend.backendNicIPConfigurationNames();
        if (!nicIpConfigs.isEmpty()) {
            System.out.printf("%s  - NIC IP configurations:%n", indent);
            nicIpConfigs.forEach((nicId, ipConfigName) -> listNicIpConfiguration(nicId, ipConfigName, indent + "    "));
        }

        if (!backend.getVirtualMachineIds().isEmpty()) {
            System.out.printf("%s  - virtual machines:%n", indent);
            backend.getVirtualMachineIds().forEach(vmId ->
                    System.out.printf("%s    - %s%n", indent, vmId));
        }

        if (backend.innerModel().loadBalancerBackendAddresses() != null
                && !backend.innerModel().loadBalancerBackendAddresses().isEmpty()) {
            System.out.printf("%s  - backend addresses:%n", indent);
            backend.innerModel().loadBalancerBackendAddresses().forEach(address ->
                    listBackendAddress(address, indent + "    "));
        }

        if (nicIpConfigs.isEmpty()
                && backend.getVirtualMachineIds().isEmpty()
                && (backend.innerModel().loadBalancerBackendAddresses() == null
                || backend.innerModel().loadBalancerBackendAddresses().isEmpty())) {
            System.out.printf("%s  - no backend members%n", indent);
        }
    }

    private void listNicIpConfiguration(String nicId, String ipConfigName, String indent) {
        NetworkInterface nic = azure.networkInterfaces().getById(nicId);
        if (nic == null) {
            System.out.printf("%s- nic=%s / ipConfig=%s / not visible%n", indent, nicId, ipConfigName);
            return;
        }

        NicIpConfiguration ipConfig = nic.ipConfigurations().get(ipConfigName);
        if (ipConfig == null) {
            System.out.printf("%s- nic=%s / ipConfig=%s / ipConfig not found%n",
                    indent,
                    nic.name(),
                    ipConfigName);
            return;
        }

        System.out.printf("%s- nic=%s / ipConfig=%s / privateIp=%s / subnet=%s%n",
                indent,
                nic.name(),
                ipConfig.name(),
                ipConfig.privateIpAddress(),
                ipConfig.subnetName());
    }

    private void listBackendAddress(LoadBalancerBackendAddress address, String indent) {
        String ipConfigId = address.networkInterfaceIpConfiguration() == null
                ? null
                : address.networkInterfaceIpConfiguration().id();
        System.out.printf("%s- name=%s / ip=%s / nicIpConfigId=%s%n",
                indent,
                address.name(),
                address.ipAddress(),
                ipConfigId);
    }

    private Map<String, LoadBalancer> loadBalancersById() {
        Map<String, LoadBalancer> loadBalancers = new LinkedHashMap<>();
        azure.loadBalancers().list().forEach(loadBalancer ->
                loadBalancers.put(normalizeId(loadBalancer.id()), loadBalancer));
        return loadBalancers;
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
