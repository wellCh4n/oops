//package com.github.wellch4n.oops.hook;
//
//import com.github.wellch4n.oops.config.KubernetesContext;
//import io.kubernetes.client.openapi.ApiClient;
//import io.kubernetes.client.openapi.apis.CoreV1Api;
//import io.kubernetes.client.openapi.models.V1Namespace;
//import io.kubernetes.client.openapi.models.V1NamespaceList;
//import io.kubernetes.client.openapi.models.V1ObjectMeta;
//import org.springframework.beans.factory.InitializingBean;
//import org.springframework.stereotype.Component;
//
//import java.util.Optional;
//
///**
// * @author wellCh4n
// * @date 2025/7/6
// */
//
//@Component
//public class SystemCheckHook implements InitializingBean {
//
//    @Override
//    public void afterPropertiesSet() throws Exception {
//        V1NamespaceList v1NamespaceList = KubernetesContext.getApi().listNamespace(null, null, null, null, null, null, null, null, null, null);
//        Optional<V1Namespace> oopsNamespace = v1NamespaceList.getItems().stream()
//                .filter(namespace -> namespace.getMetadata().getName().equals("oops"))
//                .findAny();
//        if (oopsNamespace.isEmpty()) {
//            V1Namespace namespace = new V1Namespace();
//            namespace.setMetadata(new V1ObjectMeta().name("oops"));
//            KubernetesContext.getApi().createNamespace(namespace, null, null, null, null);
//        }
//    }
//}
