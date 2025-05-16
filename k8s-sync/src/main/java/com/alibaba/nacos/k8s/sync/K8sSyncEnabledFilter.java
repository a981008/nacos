package com.alibaba.nacos.k8s.sync;

import com.alibaba.nacos.sys.env.Constants;
import com.alibaba.nacos.sys.filter.NacosPackageExcludeFilter;

import java.util.Set;

/**
 * @author Wang
 * @since 2025/5/16
 */
public class K8sSyncEnabledFilter implements NacosPackageExcludeFilter {
    @Override
    public String getResponsiblePackagePrefix() {
        return "com.alibaba.nacos.k8s.sync";
    }

    @Override
    public boolean isExcluded(String className, Set<String> annotationNames) {
        String deploymentType = System.getProperty(Constants.NACOS_DEPLOYMENT_TYPE, Constants.NACOS_DEPLOYMENT_TYPE_MERGED);
        return Constants.NACOS_DEPLOYMENT_TYPE_SERVER.equalsIgnoreCase(deploymentType);
    }
}
