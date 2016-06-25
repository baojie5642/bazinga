package org.bazinga.client.processor;

import org.bazinga.client.provider.model.ServiceWrapper;

/**
 * 根据服务名来获取该服务的提供者的信息
 * @author BazingaLyn
 * @time 2016年6月25日
 */
public interface LookupService {
	
	ServiceWrapper lookupService(String serviceName);

}
