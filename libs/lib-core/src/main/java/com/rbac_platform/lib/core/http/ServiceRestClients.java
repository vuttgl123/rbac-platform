package com.rbac_platform.lib.core.http;

import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Factory for {@link RestClient}s that call other services, with the configured timeouts and request id
 * forwarding applied uniformly.
 */
public class ServiceRestClients {

    private final HttpClientProperties properties;

    public ServiceRestClients(HttpClientProperties properties) {
        this.properties = properties;
    }

    public RestClient create(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor(new RequestIdForwardingInterceptor())
                .build();
    }
}
