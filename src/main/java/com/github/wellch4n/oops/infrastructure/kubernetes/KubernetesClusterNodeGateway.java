package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.application.port.ClusterNodeGateway;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.application.dto.NodeStatusResponse;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.api.model.Quantity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class KubernetesClusterNodeGateway implements ClusterNodeGateway {

    @Override
    public List<NodeStatusResponse> getNodes(Environment environment) {
        try (var client = com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients.from(environment.getKubernetesApiServer())) {
            var nodes = client.nodes().list().getItems();
            return nodes.stream()
                    .filter(Objects::nonNull)
                    .map(this::toResponse)
                    .sorted(Comparator.comparing(NodeStatusResponse::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                    .toList();
        }
    }

    private NodeStatusResponse toResponse(Node node) {
        NodeStatusResponse response = new NodeStatusResponse();
        response.setName(node.getMetadata() != null ? node.getMetadata().getName() : null);
        response.setCreationTimestamp(node.getMetadata() != null ? node.getMetadata().getCreationTimestamp() : null);

        response.setReady(isNodeReady(node));
        response.setRoles(extractRoles(node));
        response.setInternalIP(extractInternalIP(node));

        if (node.getStatus() != null && node.getStatus().getNodeInfo() != null) {
            response.setKubeletVersion(node.getStatus().getNodeInfo().getKubeletVersion());
            response.setOsImage(node.getStatus().getNodeInfo().getOsImage());
            response.setContainerRuntimeVersion(node.getStatus().getNodeInfo().getContainerRuntimeVersion());
        }

        Map<String, Quantity> allocatable = node.getStatus() != null ? node.getStatus().getAllocatable() : null;
        response.setCpu(quantityToString(allocatable != null ? allocatable.get("cpu") : null));
        response.setMemory(formatMemory(allocatable != null ? allocatable.get("memory") : null));
        response.setPods(quantityToString(allocatable != null ? allocatable.get("pods") : null));
        return response;
    }

    private boolean isNodeReady(Node node) {
        if (node.getStatus() == null || node.getStatus().getConditions() == null) return false;
        return node.getStatus().getConditions().stream()
                .filter(Objects::nonNull)
                .anyMatch(condition -> "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
    }

    private String extractRoles(Node node) {
        if (node.getMetadata() == null || node.getMetadata().getLabels() == null) return "-";
        var labels = node.getMetadata().getLabels();
        String prefix = "node-role.kubernetes.io/";
        var roles = labels.keySet().stream()
                .filter(label -> label != null && label.startsWith(prefix))
                .map(label -> {
                    String role = label.substring(prefix.length());
                    return role.isEmpty() ? "master" : role;
                })
                .distinct()
                .sorted()
                .toList();
        if (!roles.isEmpty()) {
            return String.join(", ", roles);
        }
        String legacy = labels.get("kubernetes.io/role");
        return legacy != null && !legacy.isEmpty() ? legacy : "-";
    }

    private String extractInternalIP(Node node) {
        if (node.getStatus() == null || node.getStatus().getAddresses() == null) return "-";
        List<NodeAddress> addresses = node.getStatus().getAddresses().stream().filter(Objects::nonNull).toList();
        var internal = addresses.stream().filter(address -> "InternalIP".equals(address.getType())).findFirst().orElse(null);
        if (internal != null && internal.getAddress() != null && !internal.getAddress().isEmpty()) {
            return internal.getAddress();
        }
        var first = addresses.stream().findFirst().orElse(null);
        return first != null && first.getAddress() != null && !first.getAddress().isEmpty() ? first.getAddress() : "-";
    }

    private String quantityToString(Quantity quantity) {
        if (quantity == null) return "-";
        return quantity.toString();
    }

    private String formatMemory(Quantity quantity) {
        if (quantity == null) return "-";
        String quantityString = quantity.toString();
        if (quantityString == null || quantityString.isEmpty()) return "-";
        if (quantityString.endsWith("Ki")) {
            try {
                BigDecimal ki = new BigDecimal(quantityString.substring(0, quantityString.length() - 2));
                BigDecimal mb = ki.divide(new BigDecimal("1024"), 1, RoundingMode.HALF_UP);
                if (mb.stripTrailingZeros().scale() <= 0) {
                    return mb.setScale(0, RoundingMode.HALF_UP) + " MB";
                }
                return mb + " MB";
            } catch (Exception _) {
                return quantityString;
            }
        }
        return quantityString;
    }
}
