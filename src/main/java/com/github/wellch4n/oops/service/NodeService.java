package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.EnvironmentRepository;
import com.github.wellch4n.oops.objects.NodeStatusResponse;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.api.model.Quantity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class NodeService {

    private final EnvironmentRepository environmentRepository;

    public NodeService(EnvironmentRepository environmentRepository) {
        this.environmentRepository = environmentRepository;
    }

    public List<NodeStatusResponse> getNodes(String environmentName) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new IllegalArgumentException("Environment not found: " + environmentName);
        }

        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            var nodes = client.nodes().list().getItems();
            return nodes.stream()
                    .filter(Objects::nonNull)
                    .map(this::toResponse)
                    .sorted(Comparator.comparing(NodeStatusResponse::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get nodes: " + e.getMessage(), e);
        }
    }

    private NodeStatusResponse toResponse(Node node) {
        NodeStatusResponse res = new NodeStatusResponse();
        res.setName(node.getMetadata() != null ? node.getMetadata().getName() : null);
        res.setCreationTimestamp(node.getMetadata() != null ? node.getMetadata().getCreationTimestamp() : null);

        res.setReady(isNodeReady(node));
        res.setRoles(extractRoles(node));
        res.setInternalIP(extractInternalIP(node));

        if (node.getStatus() != null && node.getStatus().getNodeInfo() != null) {
            res.setKubeletVersion(node.getStatus().getNodeInfo().getKubeletVersion());
            res.setOsImage(node.getStatus().getNodeInfo().getOsImage());
            res.setContainerRuntimeVersion(node.getStatus().getNodeInfo().getContainerRuntimeVersion());
        }

        Map<String, Quantity> allocatable = node.getStatus() != null ? node.getStatus().getAllocatable() : null;
        res.setCpu(quantityToString(allocatable != null ? allocatable.get("cpu") : null));
        res.setMemory(formatMemory(allocatable != null ? allocatable.get("memory") : null));
        res.setPods(quantityToString(allocatable != null ? allocatable.get("pods") : null));
        return res;
    }

    private boolean isNodeReady(Node node) {
        if (node.getStatus() == null || node.getStatus().getConditions() == null) return false;
        return node.getStatus().getConditions().stream()
                .filter(Objects::nonNull)
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    private String extractRoles(Node node) {
        if (node.getMetadata() == null || node.getMetadata().getLabels() == null) return "-";
        var labels = node.getMetadata().getLabels();
        String prefix = "node-role.kubernetes.io/";
        var roles = labels.keySet().stream()
                .filter(k -> k != null && k.startsWith(prefix))
                .map(k -> {
                    String role = k.substring(prefix.length());
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
        var internal = addresses.stream().filter(a -> "InternalIP".equals(a.getType())).findFirst().orElse(null);
        if (internal != null && internal.getAddress() != null && !internal.getAddress().isEmpty()) {
            return internal.getAddress();
        }
        var first = addresses.stream().findFirst().orElse(null);
        return first != null && first.getAddress() != null && !first.getAddress().isEmpty() ? first.getAddress() : "-";
    }

    private String quantityToString(Quantity q) {
        if (q == null) return "-";
        return q.toString();
    }

    private String formatMemory(Quantity q) {
        if (q == null) return "-";
        String s = q.toString();
        if (s == null || s.isEmpty()) return "-";
        if (s.endsWith("Ki")) {
            try {
                BigDecimal ki = new BigDecimal(s.substring(0, s.length() - 2));
                BigDecimal mb = ki.divide(new BigDecimal("1024"), 1, RoundingMode.HALF_UP);
                if (mb.stripTrailingZeros().scale() <= 0) {
                    return mb.setScale(0, RoundingMode.HALF_UP) + " MB";
                }
                return mb + " MB";
            } catch (Exception ignored) {
                return s;
            }
        }
        return s;
    }
}
