package com.playdata.calen.travel.config;

import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TravelClusterConfiguration {
    @Bean public TravelPhotoClusterPolicy travelPhotoClusterPolicy() { return new TravelPhotoClusterPolicy(); }
}
