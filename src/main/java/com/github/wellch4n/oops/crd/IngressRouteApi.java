//package com.github.wellch4n.oops.crd;
//
//import io.kubernetes.client.openapi.ApiClient;
//import io.kubernetes.client.util.generic.GenericKubernetesApi;
//
//public class IngressRouteApi extends GenericKubernetesApi<IngressRoute, IngressRouteList> {
//    public IngressRouteApi(Class<IngressRoute> ingressRouteClass, Class<IngressRouteList> ingressRouteListClass, String apiGroup, String apiVersion, String resourcePlural, ApiClient apiClient) {
//        super(ingressRouteClass, ingressRouteListClass, apiGroup, apiVersion, resourcePlural, apiClient);
//    }
//
//    public IngressRoute readNamespacedIngressRoute(String name, String namespace) {
//        var response  = this.get(namespace, name);
//        if (response.isSuccess()) {
//            return response.getObject();
//        }
//        return null;
//    }
//
//    public void upsertNamespacedIngressRoute(String name, String namespace, IngressRoute upsertObject) {
//        IngressRoute ingressRoute = readNamespacedIngressRoute(name, namespace);
//        if (ingressRoute == null) {
//            this.create(upsertObject);
//        } else {
//            upsertObject.getMetadata().setResourceVersion(ingressRoute.getMetadata().getResourceVersion());
//            this.update(upsertObject);
//        }
//    }
//}
