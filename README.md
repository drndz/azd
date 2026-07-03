# Azure Discovery project

AZD stands for Azure Discovery. This is a lightweight Java project for testing Azure management APIs.

The current utility can:

- authenticate with an Azure app registration/service principal
- create or delete one demo resource group
- create a simple Azure Traffic Manager profile pointing to external IPs/hostnames
- recreate a full Java SDK demo: Traffic Manager -> public Azure Load Balancer -> two Apache VMs
- optionally create a small Cost Management budget alert
- list the resources visible to the service principal
- recursively inspect Traffic Manager endpoints, matching Load Balancers, backend pools, NICs, and VMs
- generate Mermaid and HTML diagrams from live Azure state

## SDKs

The project uses the official Azure SDK for Java:

- `com.azure.resourcemanager:azure-resourcemanager`
- `com.azure.resourcemanager:azure-resourcemanager-trafficmanager`
- `com.azure:azure-identity`

Budget creation and the full Load Balancer/VM demo deployment use the Azure Resource Manager REST API directly with the same Azure credential. The project now keeps the active demo in the root Java module only.

## Configuration

Edit [conf/conf.properties](conf/conf.properties).

Required authentication values:

```properties
azure_tenant_id=<directory-tenant-id>
azure_subscription_id=<subscription-id>
application_id=<application-client-id>
azure_secret_val=<client-secret-value>
```

Azure portal mapping:

- `application_id`: App registrations -> your app -> Application (client) ID
- `azure_tenant_id`: Microsoft Entra ID -> Overview -> Directory (tenant) ID
- `azure_subscription_id`: Subscriptions -> your subscription -> Subscription ID
- `azure_secret_val`: App registrations -> your app -> Certificates & secrets -> secret Value

Notes:

- `azure_client_id` is accepted as an alias for `application_id`.
- `azure_client_secret` is accepted as an alias for `azure_secret_val`.
- `azure_secret_id` is not used for authentication. It is only the Azure portal ID of the secret object.

## Main Flags

Resource group:

```properties
azure_resource_group=p03-azure-wrk-rg
azure_location=centralus
azure_create_demo=false
azure_delete_demo=false
azure_full_lb_demo_recreate=false
```

Traffic Manager:

```properties
azure_traffic_manager_create=false
azure_traffic_manager_profile_name=p03-azure-wrk-tm
azure_traffic_manager_dns_label=p03-azure-wrk-tm
azure_traffic_manager_ttl_seconds=30
azure_traffic_manager_monitor_port=80
azure_traffic_manager_monitor_path=/
azure_traffic_manager_external_ips=<ip-1>,<ip-2>
```

Budget alert:

```properties
azure_budget_create=false
azure_budget_name=p03-azure-wrk-budget
azure_budget_amount_usd=5
azure_budget_contact_email=<email-for-budget-alerts>
```

Full Load Balancer and VM demo:

```properties
azure_full_lb_demo_recreate=false
azure_full_lb_demo_vm_count=2
azure_full_lb_demo_vm_size=Standard_F1als_v7
azure_full_lb_demo_regions=eastus,eastus2
azure_full_lb_demo_admin_username=azureuser
azure_full_lb_demo_vnet_cidr=10.10.0.0/16
azure_full_lb_demo_subnet_cidr=10.10.1.0/24
```

## Run

Use the Bash script wrapper from Git Bash or Cygwin:

```bash
./scripts/discover.sh
```

Script wrappers:

```bash
./scripts/discover.sh
./scripts/create.sh
./scripts/delete.sh
```

`discover.sh` runs the read-only autodiscovery/report generation path and updates `target/azure-current-report.txt`, `target/azure-demo-graph.md`, and `target/azure-demo-graph.html`.

`create.sh` temporarily sets `azure_full_lb_demo_recreate=true`, creates the sample Traffic Manager -> two regional Load Balancers -> four VM demo, then resets the flag to `false`.

`delete.sh` temporarily sets `azure_delete_demo=true`, deletes the configured demo resource group, then resets the flag to `false`.

The script does not require Maven at runtime. It runs:

```text
java -cp "lib/*" org.qypp.AzureInfraTool
```

The bundled runtime jars live in `lib/`. Maven is not used by this project; runtime dependencies are bundled in `lib/` instead:

- `com.azure:azure-identity:1.18.4`
- `com.azure.resourcemanager:azure-resourcemanager:2.62.0`
- `com.azure.resourcemanager:azure-resourcemanager-trafficmanager:2.53.8`

Their transitive runtime dependencies are also bundled in `lib/`, including Azure core/management libraries, Azure Resource Manager service modules, Jackson, MSAL4J, Netty/Reactor, SLF4J API, and JNA.

Only [AzureInfraTool.java](src/main/java/org/qypp/AzureInfraTool.java) has a `main` method. The other classes are service/helper classes called by `AzureInfraTool`.

## Create Traffic Manager

Set:

```properties
azure_delete_demo=false
azure_traffic_manager_create=true
azure_traffic_manager_external_ips=172.67.178.12,172.67.178.14
```

Then run the app.

The utility creates:

- resource group `azure_resource_group`
- Traffic Manager profile `azure_traffic_manager_profile_name`
- one external endpoint per value in `azure_traffic_manager_external_ips`
- weighted DNS routing
- HTTP health monitoring using `azure_traffic_manager_monitor_port` and `azure_traffic_manager_monitor_path`

Traffic Manager is DNS-level routing. It does not proxy HTTP traffic and does not load-balance arbitrary external IPs through Azure Load Balancer.

## Recreate Full Java SDK Demo

Set:

```properties
azure_full_lb_demo_recreate=true
azure_delete_demo=false
azure_create_demo=false
azure_traffic_manager_create=false
```

Then run the app.

This intentionally deletes and recreates the configured resource group. It creates:

- resource group `azure_resource_group`
- one regional stack per value in `azure_full_lb_demo_regions`
- each regional stack has a VNet, subnet, NSG, Standard public IP, Standard Load Balancer, outbound rule, and `azure_full_lb_demo_vm_count` Apache VMs
- one Traffic Manager profile with one weighted endpoint per regional Load Balancer public IP

After a successful recreate, set:

```properties
azure_full_lb_demo_recreate=false
```

That prevents accidental deletion on the next run.

Current verified endpoints from the last Java SDK recreate:

```text
http://23.100.27.23/ -> http_ok
http://20.10.54.209/ -> http_ok
http://p03-azure-wrk-tm.trafficmanager.net/ -> http_ok
```

## Attach Real DNS

Traffic Manager gives you a DNS name like:

```text
p03-azure-wrk-tm.trafficmanager.net
```

For your real hostname, create a `CNAME` record at your DNS provider:

```text
www.example.com CNAME p03-azure-wrk-tm.trafficmanager.net
```

For an apex/root domain such as `example.com`, normal DNS does not allow a plain `CNAME`. Use your DNS provider's `ALIAS`/`ANAME`/flattened CNAME feature if available, or put the Traffic Manager name behind a subdomain such as `www.example.com`.

Do not attach multiple Traffic Manager profiles to the same DNS name with multiple CNAMEs. A DNS name cannot have more than one CNAME. If you need multiple regional or hierarchical routes, use one Traffic Manager profile with multiple endpoints, or use nested Traffic Manager profiles and point your real DNS name to the parent profile.

## List Created Resources

Every normal run prints a targeted section:

```text
Configured demo resources:
```

That section shows:

- configured resource group
- Traffic Manager FQDN
- routing method
- monitor status
- TTL
- external endpoints, weights, and health status

The app also prints broader subscription lists:

- resource groups
- public IP addresses
- virtual networks
- load balancers
- Traffic Manager profiles

## Recursive Topology Listing

The root Java utility also includes a read-only recursive lister:

[src/main/java/org/qypp/AzureRecursiveTopologyLister.java](src/main/java/org/qypp/AzureRecursiveTopologyLister.java)

It starts from the configured Traffic Manager profile:

```properties
azure_resource_group=p03-azure-wrk-rg
azure_traffic_manager_profile_name=p03-azure-wrk-tm
```

Then it walks:

```text
Traffic Manager
-> Traffic Manager endpoints
-> matching Azure Load Balancer, if the endpoint points to one
-> Load Balancer public frontends
-> Load Balancer rules
-> backend pools
-> backend NIC IP configurations / VM IDs / backend addresses
```

Supported Load Balancer detection:

- Traffic Manager Azure endpoint target resource ID equals an Azure Load Balancer ID.
- Traffic Manager external endpoint target matches an Azure Public IP address or FQDN assigned to an Azure Load Balancer frontend.

If the Traffic Manager endpoint is just an arbitrary external IP/hostname, the lister prints:

```text
no Azure Load Balancer matched this external target
```

That is expected for endpoints such as Cloudflare public IPs.

## Graph Output

Every normal run writes:

- [target/azure-demo-graph.md](target/azure-demo-graph.md)
- [target/azure-demo-graph.html](target/azure-demo-graph.html)

The Markdown file contains a Mermaid diagram.

The HTML file renders the same graph in a browser using Mermaid from CDN:

```text
target/azure-demo-graph.html
```

The graph is generated from live Azure state, not only from local config.

## Delete Demo Resources

To terminate everything this demo created, set:

```properties
azure_delete_demo=true
azure_traffic_manager_create=false
azure_create_demo=false
```

Then run the app.

The utility deletes:

```text
azure_resource_group
```

Deleting the resource group deletes contained demo resources, including the Traffic Manager profile.

After deletion, set:

```properties
azure_delete_demo=false
```

The full demo also deletes through the same resource group cleanup because all demo resources are inside `azure_resource_group`.

## Azure RBAC

The app authenticates as the service principal, not as your portal user.

For role assignment, select the enterprise application/service principal, not your guest user.

Known app values used during testing:

```text
Application/client ID: c3f95adc-602c-40f2-aefa-68eb9675b027
Service principal object ID: 571766e5-acbf-4d49-ab59-9ad321b03b83
```

Azure Portal path:

```text
Subscriptions -> select subscription -> Access control (IAM) -> Add role assignment
```

Useful roles:

- **Reader**: list resources only
- **Contributor**: create/delete demo resource group and Traffic Manager profile
- **Cost Management Contributor**: create/update budgets

If the member picker does not show the app by name, search by the service principal object ID:

```text
571766e5-acbf-4d49-ab59-9ad321b03b83
```

## Budget Alerts

Set:

```properties
azure_budget_create=true
azure_budget_amount_usd=5
azure_budget_contact_email=<email>
```

The utility creates a monthly subscription budget with alerts at:

- 50%
- 80%
- 100%

This requires a role containing:

```text
Microsoft.CostManagement/budgets/write
```

Use **Cost Management Contributor** if you want the Java utility to create the budget.

Budget alerts notify; they do not hard-stop spending by themselves.

## Cost Notes

Resource groups are free by themselves.

Traffic Manager has small recurring charges based mainly on:

- DNS query volume
- monitored endpoints

The earlier Traffic Manager-only test used two external endpoints. That is expected to be low cost, but it is still a paid Azure service unless covered by active free credits.

The full demo runs two VMs plus a Standard public IP, Standard Load Balancer, managed OS disks, Traffic Manager, and bandwidth. Stop/delete it after testing if you do not want ongoing charges.

The current VM size is:

```properties
azure_full_lb_demo_vm_size=Standard_F1als_v7
```

In Central US, current retail compute pricing is about `$0.0605/hour` per VM, or `$0.121/hour` for two VMs. The cheaper B1ls/B1s sizes are around the `$5/month` range, but this subscription currently reports them as unavailable/restricted in the checked regions.

Cheapest safe defaults after testing:

```properties
azure_create_demo=false
azure_traffic_manager_create=false
azure_budget_create=false
azure_delete_demo=false
azure_full_lb_demo_recreate=false
```

## Code Structure

| Class | Has `main`? | Purpose |
| --- | --- | --- |
| [AzureInfraTool.java](src/main/java/org/qypp/AzureInfraTool.java) | Yes | Executable entry point. Loads config, authenticates, checks flags, delegates to service classes, and prints broad subscription lists. |
| [AzureInfraCreator.java](src/main/java/org/qypp/AzureInfraCreator.java) | No | Creates the configured resource group and Traffic Manager profile with external endpoints, and can recreate the full Traffic Manager -> Load Balancer -> Apache VM demo. |
| [AzureInfraDeleter.java](src/main/java/org/qypp/AzureInfraDeleter.java) | No | Deletes the configured demo resource group and everything inside it. |
| [AzureRepresentationGenerator.java](src/main/java/org/qypp/AzureRepresentationGenerator.java) | No | Prints configured demo resources and writes `target/azure-demo-graph.md` plus `target/azure-demo-graph.html`. |
| [AzureRecursiveTopologyLister.java](src/main/java/org/qypp/AzureRecursiveTopologyLister.java) | No | Starts at the configured Traffic Manager profile and recursively lists matching Load Balancer frontends, rules, backend pools, and backend members. |
| [AzureConfig.java](src/main/java/org/qypp/AzureConfig.java) | No | Loads/parses `conf/conf.properties`, supports environment-variable fallback, and provides small config helpers. |
| [AzureLookup.java](src/main/java/org/qypp/AzureLookup.java) | No | Wraps Azure SDK lookups so missing resources return `null` instead of failing the run. |

Flow:

```text
AzureInfraTool
-> AzureConfig
-> Azure SDK authentication
-> AzureInfraDeleter       when azure_delete_demo=true
-> budget REST call        when azure_budget_create=true
-> full ARM deployment     when azure_full_lb_demo_recreate=true
-> AzureInfraCreator       when azure_create_demo=true or azure_traffic_manager_create=true
-> AzureRepresentationGenerator
-> AzureRecursiveTopologyLister
-> broad subscription listing
```
