package org.qypp;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.management.Region;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.azure.resourcemanager.trafficmanager.TrafficManager;
import com.azure.resourcemanager.trafficmanager.models.TrafficManagerProfile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Creates the demo Azure infrastructure controlled by {@code conf/conf.properties}.
 *
 * <p>This class is not executable by itself. {@link AzureInfraTool} constructs it after
 * authentication and calls it when {@code azure_create_demo=true} or
 * {@code azure_traffic_manager_create=true}.</p>
 *
 * <p>The Traffic Manager path creates or reuses the configured resource group,
 * creates one Traffic Manager profile, and adds one external endpoint per
 * configured IP address or hostname.</p>
 */
public class AzureInfraCreator {
    private final AzureResourceManager azure;
    private final TrafficManager trafficManager;
    private final Properties config;
    private final TokenCredential credential;
    private final String subscriptionId;

    public AzureInfraCreator(
            AzureResourceManager azure,
            TrafficManager trafficManager,
            Properties config,
            TokenCredential credential,
            String subscriptionId) {
        this.azure = azure;
        this.trafficManager = trafficManager;
        this.config = config;
        this.credential = credential;
        this.subscriptionId = subscriptionId;
    }

    public void createResourceGroup() {
        String resourceGroupName = AzureConfig.required(config, "azure_resource_group");
        String location = AzureConfig.required(config, "azure_location");

        ResourceGroup resourceGroup = azure.resourceGroups()
                .define(resourceGroupName)
                .withRegion(Region.fromName(location))
                .create();

        System.out.printf("Created or updated resource group: %s (%s)%n%n",
                resourceGroup.name(),
                resourceGroup.regionName());
    }

    public void createTrafficManagerProfile() {
        String resourceGroupName = AzureConfig.required(config, "azure_resource_group");
        String location = AzureConfig.required(config, "azure_location");
        String profileName = AzureConfig.required(config, "azure_traffic_manager_profile_name");
        String dnsLabel = AzureConfig.required(config, "azure_traffic_manager_dns_label");
        int ttlSeconds = Integer.parseInt(AzureConfig.required(config, "azure_traffic_manager_ttl_seconds"));
        int monitorPort = Integer.parseInt(AzureConfig.required(config, "azure_traffic_manager_monitor_port"));
        String monitorPath = AzureConfig.required(config, "azure_traffic_manager_monitor_path");
        List<String> targets = AzureConfig.csvValues(AzureConfig.required(config, "azure_traffic_manager_external_ips"));

        if (targets.isEmpty()) {
            throw new IllegalStateException("azure_traffic_manager_external_ips must contain at least one IP or hostname");
        }

        azure.resourceGroups()
                .define(resourceGroupName)
                .withRegion(Region.fromName(location))
                .create();

        TrafficManagerProfile existing = AzureLookup.trafficManagerProfileOrNull(
                trafficManager,
                resourceGroupName,
                profileName);
        if (existing != null) {
            System.out.printf("Traffic Manager profile already exists: %s (%s)%n%n",
                    existing.name(),
                    existing.fqdn());
            return;
        }

        TrafficManagerProfile.DefinitionStages.WithCreate definition = trafficManager.profiles()
                .define(profileName)
                .withExistingResourceGroup(resourceGroupName)
                .withLeafDomainLabel(dnsLabel)
                .withWeightBasedRouting()
                .defineExternalTargetEndpoint(AzureConfig.endpointName(1, targets.get(0)))
                .toFqdn(targets.get(0))
                .fromRegion(Region.fromName(location))
                .withRoutingWeight(1)
                .attach();

        for (int index = 1; index < targets.size(); index++) {
            String target = targets.get(index);
            definition = definition
                    .defineExternalTargetEndpoint(AzureConfig.endpointName(index + 1, target))
                    .toFqdn(target)
                    .fromRegion(Region.fromName(location))
                    .withRoutingWeight(1)
                    .attach();
        }

        TrafficManagerProfile profile = definition
                .withHttpMonitoring(monitorPort, monitorPath)
                .withTimeToLive(ttlSeconds)
                .create();

        System.out.printf("Created Traffic Manager profile: %s (%s)%n", profile.name(), profile.fqdn());
        System.out.println("External endpoints:");
        profile.externalEndpoints().values().forEach(endpoint ->
                System.out.printf("- %s -> %s%n", endpoint.name(), endpoint.fqdn()));
        System.out.println();
    }

    public void recreateFullLoadBalancedTrafficManagerDemo() throws IOException {
        String resourceGroupName = AzureConfig.required(config, "azure_resource_group");
        String location = AzureConfig.required(config, "azure_location");
        ResourceGroup existing = AzureLookup.resourceGroupOrNull(azure, resourceGroupName);
        if (existing != null) {
            System.out.printf("Deleting existing resource group before full recreate: %s%n", resourceGroupName);
            azure.resourceGroups().deleteByName(resourceGroupName);
            System.out.printf("Deleted existing resource group: %s%n%n", resourceGroupName);
        }

        azure.resourceGroups()
                .define(resourceGroupName)
                .withRegion(Region.fromName(location))
                .create();

        String deploymentName = "full-lb-atm-demo";
        String adminPassword = randomPassword();
        String template = fullDemoTemplate(adminPassword);
        String requestBody = """
                {
                  "properties": {
                    "mode": "Incremental",
                    "template": %s
                  }
                }
                """.formatted(template);

        URI uri = URI.create("https://management.azure.com/subscriptions/"
                + url(subscriptionId)
                + "/resourcegroups/"
                + url(resourceGroupName)
                + "/providers/Microsoft.Resources/deployments/"
                + url(deploymentName)
                + "?api-version=2022-09-01");

        String accessToken = credential
                .getToken(new TokenRequestContext().addScopes("https://management.azure.com/.default"))
                .block()
                .getToken();

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            System.out.println("Deploying full demo: Traffic Manager -> Load Balancer -> Apache VMs");
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("ARM deployment failed to start. HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            waitForDeployment(accessToken, resourceGroupName, deploymentName);
            System.out.printf("Full demo deployment completed: %s%n%n", deploymentName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while deploying full demo", e);
        }
    }

    private void waitForDeployment(String accessToken, String resourceGroupName, String deploymentName)
            throws IOException, InterruptedException {
        URI uri = URI.create("https://management.azure.com/subscriptions/"
                + url(subscriptionId)
                + "/resourcegroups/"
                + url(resourceGroupName)
                + "/providers/Microsoft.Resources/deployments/"
                + url(deploymentName)
                + "?api-version=2022-09-01");

        HttpClient client = HttpClient.newHttpClient();
        for (int attempt = 1; attempt <= 120; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            String state = extractProvisioningState(body);
            System.out.printf("Deployment state: %s%n", state);
            if ("Succeeded".equalsIgnoreCase(state)) {
                return;
            }
            if ("Failed".equalsIgnoreCase(state)
                    || "Canceled".equalsIgnoreCase(state)
                    || body.contains("\"error\"")) {
                throw new IllegalStateException("ARM deployment did not succeed. HTTP "
                        + response.statusCode() + ": " + body);
            }
            Thread.sleep(10_000);
        }
        throw new IllegalStateException("Timed out waiting for ARM deployment: " + deploymentName);
    }

    private String fullDemoTemplate(String adminPassword) {
        String profileName = AzureConfig.required(config, "azure_traffic_manager_profile_name");
        String dnsLabel = AzureConfig.required(config, "azure_traffic_manager_dns_label");
        int ttlSeconds = Integer.parseInt(AzureConfig.required(config, "azure_traffic_manager_ttl_seconds"));
        int monitorPort = Integer.parseInt(AzureConfig.required(config, "azure_traffic_manager_monitor_port"));
        String monitorPath = AzureConfig.required(config, "azure_traffic_manager_monitor_path");
        int vmCount = Integer.parseInt(AzureConfig.required(config, "azure_full_lb_demo_vm_count"));
        String vmSize = AzureConfig.required(config, "azure_full_lb_demo_vm_size");
        String imageSku = ubuntuImageSku(vmSize);
        String adminUsername = AzureConfig.required(config, "azure_full_lb_demo_admin_username");
        List<String> regions = demoRegions();

        String customData = Base64.getEncoder().encodeToString("""
                #!/bin/bash
                set -eux
                apt-get update
                DEBIAN_FRONTEND=noninteractive apt-get install -y apache2
                echo http_ok > /var/www/html/index.html
                systemctl enable apache2
                systemctl restart apache2
                """.getBytes(StandardCharsets.UTF_8));

        StringBuilder regionalResources = new StringBuilder();
        StringBuilder trafficManagerEndpoints = new StringBuilder();
        for (int regionIndex = 1; regionIndex <= regions.size(); regionIndex++) {
            if (!regionalResources.isEmpty()) {
                regionalResources.append(",");
            }
            if (!trafficManagerEndpoints.isEmpty()) {
                trafficManagerEndpoints.append(",");
            }
            String region = regions.get(regionIndex - 1);
            regionalResources.append(regionalStackResources(
                    regionIndex,
                    region,
                    vmCount,
                    vmSize,
                    imageSku,
                    adminUsername,
                    adminPassword,
                    customData));
            trafficManagerEndpoints.append(trafficManagerEndpointResource(profileName, regionIndex, region));
        }

        return """
                {
                  "$schema": "https://schema.management.azure.com/schemas/2019-04-01/deploymentTemplate.json#",
                  "contentVersion": "1.0.0.0",
                  "resources": [
                    %s,
                    {
                      "type": "Microsoft.Network/trafficManagerProfiles",
                      "apiVersion": "2022-04-01",
                      "name": "%s",
                      "location": "global",
                      "properties": {
                        "profileStatus": "Enabled",
                        "trafficRoutingMethod": "Weighted",
                        "dnsConfig": {
                          "relativeName": "%s",
                          "ttl": %d
                        },
                        "monitorConfig": {
                          "protocol": "HTTP",
                          "port": %d,
                          "path": "%s"
                        }
                      }
                    },
                    %s
                  ],
                  "outputs": {
                    "trafficManagerFqdn": {
                      "type": "string",
                      "value": "%s.trafficmanager.net"
                    }
                  }
                }
                """.formatted(
                regionalResources,
                j(profileName),
                j(dnsLabel),
                ttlSeconds,
                monitorPort,
                j(monitorPath),
                trafficManagerEndpoints,
                j(dnsLabel));
    }

    private List<String> demoRegions() {
        String configuredRegions = AzureConfig.value(config, "azure_full_lb_demo_regions");
        if (!configuredRegions.isBlank()) {
            return AzureConfig.csvValues(configuredRegions);
        }
        return List.of(AzureConfig.required(config, "azure_location"));
    }

    private String regionalStackResources(
            int regionIndex,
            String location,
            int vmCount,
            String vmSize,
            String imageSku,
            String adminUsername,
            String adminPassword,
            String customData) {
        String suffix = regionSuffix(regionIndex);
        String vnetCidr = regionIndex == 1
                ? AzureConfig.required(config, "azure_full_lb_demo_vnet_cidr")
                : "10." + (regionIndex * 10) + ".0.0/16";
        String subnetCidr = regionIndex == 1
                ? AzureConfig.required(config, "azure_full_lb_demo_subnet_cidr")
                : "10." + (regionIndex * 10) + ".1.0/24";

        StringBuilder resources = new StringBuilder();
        resources.append(networkSecurityGroupResource(suffix, location));
        resources.append(",");
        resources.append(vnetResource(suffix, location, vnetCidr, subnetCidr));
        resources.append(",");
        resources.append(publicIpResource(suffix, location));
        resources.append(",");
        resources.append(loadBalancerResource(suffix, location));
        for (int vmIndex = 1; vmIndex <= vmCount; vmIndex++) {
            resources.append(",");
            resources.append(nicResource(suffix, vmIndex, location));
            resources.append(",");
            resources.append(vmResource(suffix, vmIndex, location, vmSize, imageSku, adminUsername, adminPassword, customData));
        }
        return resources.toString();
    }

    private String networkSecurityGroupResource(String suffix, String location) {
        return """
                {
                  "type": "Microsoft.Network/networkSecurityGroups",
                  "apiVersion": "2023-09-01",
                  "name": "%s-nsg",
                  "location": "%s",
                  "properties": {
                    "securityRules": [
                      {
                        "name": "allow-http",
                        "properties": {
                          "priority": 100,
                          "access": "Allow",
                          "direction": "Inbound",
                          "protocol": "Tcp",
                          "sourcePortRange": "*",
                          "destinationPortRange": "80",
                          "sourceAddressPrefix": "*",
                          "destinationAddressPrefix": "*"
                        }
                      }
                    ]
                  }
                }
                """.formatted(j(suffix), j(location));
    }

    private String vnetResource(String suffix, String location, String vnetCidr, String subnetCidr) {
        return """
                {
                  "type": "Microsoft.Network/virtualNetworks",
                  "apiVersion": "2023-09-01",
                  "name": "%s-vnet",
                  "location": "%s",
                  "dependsOn": [
                    "[resourceId('Microsoft.Network/networkSecurityGroups', '%s-nsg')]"
                  ],
                  "properties": {
                    "addressSpace": {
                      "addressPrefixes": ["%s"]
                    },
                    "subnets": [
                      {
                        "name": "default",
                        "properties": {
                          "addressPrefix": "%s",
                          "networkSecurityGroup": {
                            "id": "[resourceId('Microsoft.Network/networkSecurityGroups', '%s-nsg')]"
                          }
                        }
                      }
                    ]
                  }
                }
                """.formatted(j(suffix), j(location), j(suffix), j(vnetCidr), j(subnetCidr), j(suffix));
    }

    private String publicIpResource(String suffix, String location) {
        return """
                {
                  "type": "Microsoft.Network/publicIPAddresses",
                  "apiVersion": "2023-09-01",
                  "name": "%s-lb-pip",
                  "location": "%s",
                  "sku": {
                    "name": "Standard"
                  },
                  "properties": {
                    "publicIPAllocationMethod": "Static",
                    "publicIPAddressVersion": "IPv4"
                  }
                }
                """.formatted(j(suffix), j(location));
    }

    private String loadBalancerResource(String suffix, String location) {
        return """
                {
                  "type": "Microsoft.Network/loadBalancers",
                  "apiVersion": "2023-09-01",
                  "name": "%s-lb",
                  "location": "%s",
                  "sku": {
                    "name": "Standard"
                  },
                  "dependsOn": [
                    "[resourceId('Microsoft.Network/publicIPAddresses', '%s-lb-pip')]"
                  ],
                  "properties": {
                    "frontendIPConfigurations": [
                      {
                        "name": "public-frontend",
                        "properties": {
                          "publicIPAddress": {
                            "id": "[resourceId('Microsoft.Network/publicIPAddresses', '%s-lb-pip')]"
                          }
                        }
                      }
                    ],
                    "backendAddressPools": [
                      {
                        "name": "apache-backend"
                      }
                    ],
                    "probes": [
                      {
                        "name": "http-probe",
                        "properties": {
                          "protocol": "Http",
                          "port": 80,
                          "requestPath": "/",
                          "intervalInSeconds": 5,
                          "numberOfProbes": 2
                        }
                      }
                    ],
                    "loadBalancingRules": [
                      {
                        "name": "http-rule",
                        "properties": {
                          "protocol": "Tcp",
                          "frontendPort": 80,
                          "backendPort": 80,
                          "disableOutboundSNAT": true,
                          "enableFloatingIP": false,
                          "idleTimeoutInMinutes": 4,
                          "frontendIPConfiguration": {
                            "id": "[resourceId('Microsoft.Network/loadBalancers/frontendIPConfigurations', '%s-lb', 'public-frontend')]"
                          },
                          "backendAddressPool": {
                            "id": "[resourceId('Microsoft.Network/loadBalancers/backendAddressPools', '%s-lb', 'apache-backend')]"
                          },
                          "probe": {
                            "id": "[resourceId('Microsoft.Network/loadBalancers/probes', '%s-lb', 'http-probe')]"
                          }
                        }
                      }
                    ],
                    "outboundRules": [
                      {
                        "name": "outbound-internet",
                        "properties": {
                          "allocatedOutboundPorts": 1024,
                          "protocol": "All",
                          "frontendIPConfigurations": [
                            {
                              "id": "[resourceId('Microsoft.Network/loadBalancers/frontendIPConfigurations', '%s-lb', 'public-frontend')]"
                            }
                          ],
                          "backendAddressPool": {
                            "id": "[resourceId('Microsoft.Network/loadBalancers/backendAddressPools', '%s-lb', 'apache-backend')]"
                          }
                        }
                      }
                    ]
                  }
                }
                """.formatted(
                j(suffix),
                j(location),
                j(suffix),
                j(suffix),
                j(suffix),
                j(suffix),
                j(suffix),
                j(suffix),
                j(suffix));
    }

    private String trafficManagerEndpointResource(String profileName, int regionIndex, String location) {
        String suffix = regionSuffix(regionIndex);
        return """
                {
                  "type": "Microsoft.Network/trafficManagerProfiles/ExternalEndpoints",
                  "apiVersion": "2022-04-01",
                  "name": "%s/%s-endpoint",
                  "dependsOn": [
                    "[resourceId('Microsoft.Network/trafficManagerProfiles', '%s')]",
                    "[resourceId('Microsoft.Network/publicIPAddresses', '%s-lb-pip')]"
                  ],
                  "properties": {
                    "endpointStatus": "Enabled",
                    "target": "[reference(resourceId('Microsoft.Network/publicIPAddresses', '%s-lb-pip')).ipAddress]",
                    "endpointLocation": "%s",
                    "weight": 1
                  }
                }
                """.formatted(j(profileName), j(suffix), j(profileName), j(suffix), j(suffix), j(location));
    }

    private String nicResource(String suffix, int index, String location) {
        return """
                {
                  "type": "Microsoft.Network/networkInterfaces",
                  "apiVersion": "2023-09-01",
                  "name": "%s-vm%d-nic",
                  "location": "%s",
                  "dependsOn": [
                    "[resourceId('Microsoft.Network/virtualNetworks', '%s-vnet')]",
                    "[resourceId('Microsoft.Network/loadBalancers', '%s-lb')]"
                  ],
                  "properties": {
                    "ipConfigurations": [
                      {
                        "name": "ipconfig1",
                        "properties": {
                          "privateIPAllocationMethod": "Dynamic",
                          "subnet": {
                            "id": "[resourceId('Microsoft.Network/virtualNetworks/subnets', '%s-vnet', 'default')]"
                          },
                          "loadBalancerBackendAddressPools": [
                            {
                              "id": "[resourceId('Microsoft.Network/loadBalancers/backendAddressPools', '%s-lb', 'apache-backend')]"
                            }
                          ]
                        }
                      }
                    ]
                  }
                }
                """.formatted(j(suffix), index, j(location), j(suffix), j(suffix), j(suffix), j(suffix));
    }

    private String vmResource(
            String suffix,
            int index,
            String location,
            String vmSize,
            String imageSku,
            String adminUsername,
            String adminPassword,
            String customData) {
        return """
                {
                  "type": "Microsoft.Compute/virtualMachines",
                  "apiVersion": "2023-09-01",
                  "name": "%s-vm%d",
                  "location": "%s",
                  "dependsOn": [
                    "[resourceId('Microsoft.Network/networkInterfaces', '%s-vm%d-nic')]"
                  ],
                  "properties": {
                    "hardwareProfile": {
                      "vmSize": "%s"
                    },
                    "osProfile": {
                      "computerName": "%s-vm%d",
                      "adminUsername": "%s",
                      "adminPassword": "%s",
                      "customData": "%s",
                      "linuxConfiguration": {
                        "disablePasswordAuthentication": false
                      }
                    },
                    "storageProfile": {
                      "imageReference": {
                        "publisher": "Canonical",
                        "offer": "0001-com-ubuntu-server-jammy",
                        "sku": "%s",
                        "version": "latest"
                      },
                      "osDisk": {
                        "createOption": "FromImage",
                        "deleteOption": "Delete",
                        "managedDisk": {
                          "storageAccountType": "Standard_LRS"
                        }
                      }
                    },
                    "networkProfile": {
                      "networkInterfaces": [
                        {
                          "id": "[resourceId('Microsoft.Network/networkInterfaces', '%s-vm%d-nic')]",
                          "properties": {
                            "deleteOption": "Delete"
                          }
                        }
                      ]
                    }
                  }
                }
                """.formatted(
                j(suffix),
                index,
                j(location),
                j(suffix),
                index,
                j(vmSize),
                j(suffix),
                index,
                j(adminUsername),
                j(adminPassword),
                j(customData),
                j(imageSku),
                j(suffix),
                index);
    }

    private static String regionSuffix(int regionIndex) {
        return "p03-demo-r" + regionIndex;
    }

    private static String ubuntuImageSku(String vmSize) {
        String normalized = vmSize.toLowerCase(Locale.ROOT);
        if (normalized.contains("_v6") || normalized.contains("_v7") || normalized.contains("ts_v2")) {
            return "22_04-lts-gen2";
        }
        return "22_04-lts";
    }

    private static String randomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!#$%";
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder("Az9!");
        for (int i = 0; i < 24; i++) {
            password.append(chars.charAt(random.nextInt(chars.length())));
        }
        return password.toString();
    }

    private static String extractProvisioningState(String body) {
        String marker = "\"provisioningState\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            return "Unknown";
        }
        int valueStart = start + marker.length();
        int valueEnd = body.indexOf('"', valueStart);
        if (valueEnd < 0) {
            return "Unknown";
        }
        return body.substring(valueStart, valueEnd);
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String j(String value) {
        return AzureConfig.jsonEscape(value);
    }
}
